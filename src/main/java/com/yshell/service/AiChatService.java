package com.yshell.service;

import com.yshell.config.AppConfig;
import com.yshell.config.AppSettings;
import com.yshell.model.ai.AiChatMessage;
import com.yshell.model.ai.AiConversation;
import com.yshell.model.ai.AiImageAttachment;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

public class AiChatService {
    private static final int ANTHROPIC_MAX_TOKENS = 8192;
    private static final AiChatService INSTANCE = new AiChatService();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new NamedDaemonThreadFactory());
    private final AppSettings settings = AppSettings.getInstance();

    private AiChatService() {
    }

    public static AiChatService getInstance() {
        return INSTANCE;
    }

    public ChatRequestHandle chat(AiConversation conversation,
                                  AiChatMessage userMessage,
                                  String connId,
                                  AppConfig.AiModelConnection connection,
                                  boolean stream,
                                  String thinkingMode,
                                  ResponseCallback callback) {
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<Void> completion = CompletableFuture.runAsync(() -> {
            try {
                checkCancelled(cancelled);
                validateSettings(connection);
                validateImageInput(connection, userMessage);
                List<ChatMessage> messages = toLangChainMessages(
                        conversation, userMessage, connId, connection.imageInputSupported);
                ChatRequest request = buildRequest(messages, connection);
                if (stream) {
                    streamChat(request, connection, thinkingMode, callback, cancelled);
                } else {
                    blockingChat(request, connection, thinkingMode, callback, cancelled);
                }
            } catch (CancellationException ignored) {
            } catch (Exception e) {
                if (!cancelled.get()) {
                    callback.onError(e);
                }
            }
        }, executor);
        return new ChatRequestHandle(cancelled, completion);
    }

    private void blockingChat(ChatRequest request, AppConfig.AiModelConnection connection, String thinkingMode,
                              ResponseCallback callback, AtomicBoolean cancelled) {
        checkCancelled(cancelled);
        boolean thinking = isThinkingEnabled(thinkingMode);
        ChatModel chatModel = chatModel(connection, thinkingMode);
        ChatResponse response = chatModel.chat(request);
        checkCancelled(cancelled);
        AiMessage aiMessage = response.aiMessage();
        if (thinking && aiMessage.thinking() != null && !aiMessage.thinking().isBlank()) {
            callback.onThinking(aiMessage.thinking());
        }
        callback.onComplete(safeText(aiMessage.text()), safeText(aiMessage.thinking()));
    }

    private ChatModel chatModel(AppConfig.AiModelConnection connection, String thinkingMode) {
        return switch (safeFormat(connection)) {
            case "OPENAI_RESPONSES" -> responsesChatModel(connection, thinkingMode);
            case "ANTHROPIC_MESSAGES" -> anthropicChatModel(connection, thinkingMode);
            case "GEMINI_NATIVE" -> geminiChatModel(connection, thinkingMode);
            default -> chatCompletionsModel(connection, thinkingMode);
        };
    }

    private ChatModel chatCompletionsModel(AppConfig.AiModelConnection connection, String thinkingMode) {
        boolean thinking = isThinkingEnabled(thinkingMode);
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(connection.baseUrl)
                .apiKey(connection.apiKey)
                .modelName(connection.model)
                .returnThinking(thinking)
                .timeout(Duration.ofSeconds(120))
                .maxRetries(1);
        if (thinking) {
            builder.reasoningEffort(openAiReasoningEffort(thinkingMode));
        }
        return builder.build();
    }

    private ChatModel responsesChatModel(AppConfig.AiModelConnection connection, String thinkingMode) {
        boolean thinking = isThinkingEnabled(thinkingMode);
        OpenAiResponsesChatModel.Builder builder = OpenAiResponsesChatModel.builder()
                .baseUrl(connection.baseUrl)
                .apiKey(connection.apiKey)
                .modelName(connection.model);
        if (thinking) {
            builder.reasoningEffort(openAiReasoningEffort(thinkingMode))
                    .reasoningSummary("auto");
        }
        return builder.build();
    }

    private ChatModel anthropicChatModel(AppConfig.AiModelConnection connection, String thinkingMode) {
        boolean thinking = isThinkingEnabled(thinkingMode);
        AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                .baseUrl(connection.baseUrl)
                .apiKey(connection.apiKey)
                .modelName(connection.model)
                .maxTokens(ANTHROPIC_MAX_TOKENS)
                .returnThinking(thinking)
                .timeout(Duration.ofSeconds(120))
                .maxRetries(1);
        if (thinking) {
            builder.thinkingType("adaptive");
        }
        return builder.build();
    }

    private ChatModel geminiChatModel(AppConfig.AiModelConnection connection, String thinkingMode) {
        boolean thinking = isThinkingEnabled(thinkingMode);
        GoogleAiGeminiChatModel.GoogleAiGeminiChatModelBuilder builder = GoogleAiGeminiChatModel.builder()
                .baseUrl(connection.baseUrl)
                .apiKey(connection.apiKey)
                .modelName(connection.model)
                .returnThinking(thinking)
                .timeout(Duration.ofSeconds(120))
                .maxRetries(1);
        if (thinking) {
            builder.thinkingConfig(geminiThinkingConfig(thinkingMode));
        }
        return builder.build();
    }

    private void streamChat(ChatRequest request, AppConfig.AiModelConnection connection, String thinkingMode,
                            ResponseCallback callback, AtomicBoolean cancelled) {
        boolean thinking = isThinkingEnabled(thinkingMode);
        StreamingChatModel chatModel = streamingChatModel(connection, thinkingMode);
        StringBuilder answer = new StringBuilder();
        StringBuilder thought = new StringBuilder();
        chatModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                checkCancelled(cancelled);
                if (partialResponse == null || partialResponse.isEmpty()) {
                    return;
                }
                answer.append(partialResponse);
                callback.onPartial(partialResponse);
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                checkCancelled(cancelled);
                if (!thinking || partialThinking == null || partialThinking.text() == null) {
                    return;
                }
                thought.append(partialThinking.text());
                callback.onThinking(partialThinking.text());
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                checkCancelled(cancelled);
                String finalText = answer.toString();
                String finalThinking = thought.toString();
                if (completeResponse != null && completeResponse.aiMessage() != null) {
                    AiMessage aiMessage = completeResponse.aiMessage();
                    if (finalText.isBlank()) {
                        finalText = safeText(aiMessage.text());
                    }
                    if (finalThinking.isBlank()) {
                        finalThinking = safeText(aiMessage.thinking());
                    }
                }
                callback.onComplete(finalText, finalThinking);
            }

            @Override
            public void onError(Throwable error) {
                if (!cancelled.get()) {
                    callback.onError(error);
                }
            }
        });
    }

    private void checkCancelled(AtomicBoolean cancelled) {
        if (cancelled.get()) {
            throw new CancellationException("AI request cancelled");
        }
    }

    private StreamingChatModel streamingChatModel(AppConfig.AiModelConnection connection, String thinkingMode) {
        return switch (safeFormat(connection)) {
            case "OPENAI_RESPONSES" -> responsesStreamingChatModel(connection, thinkingMode);
            case "ANTHROPIC_MESSAGES" -> anthropicStreamingChatModel(connection, thinkingMode);
            case "GEMINI_NATIVE" -> geminiStreamingChatModel(connection, thinkingMode);
            default -> chatCompletionsStreamingModel(connection, thinkingMode);
        };
    }

    private StreamingChatModel chatCompletionsStreamingModel(AppConfig.AiModelConnection connection, String thinkingMode) {
        boolean thinking = isThinkingEnabled(thinkingMode);
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .baseUrl(connection.baseUrl)
                .apiKey(connection.apiKey)
                .modelName(connection.model)
                .returnThinking(thinking)
                .timeout(Duration.ofSeconds(120));
        if (thinking) {
            builder.reasoningEffort(openAiReasoningEffort(thinkingMode));
        }
        return builder.build();
    }

    private StreamingChatModel responsesStreamingChatModel(AppConfig.AiModelConnection connection, String thinkingMode) {
        boolean thinking = isThinkingEnabled(thinkingMode);
        OpenAiResponsesStreamingChatModel.Builder builder = OpenAiResponsesStreamingChatModel.builder()
                .baseUrl(connection.baseUrl)
                .apiKey(connection.apiKey)
                .modelName(connection.model);
        if (thinking) {
            builder.reasoningEffort(openAiReasoningEffort(thinkingMode))
                    .reasoningSummary("auto");
        }
        return builder.build();
    }

    private StreamingChatModel anthropicStreamingChatModel(AppConfig.AiModelConnection connection, String thinkingMode) {
        boolean thinking = isThinkingEnabled(thinkingMode);
        AnthropicStreamingChatModel.AnthropicStreamingChatModelBuilder builder = AnthropicStreamingChatModel.builder()
                .baseUrl(connection.baseUrl)
                .apiKey(connection.apiKey)
                .modelName(connection.model)
                .maxTokens(ANTHROPIC_MAX_TOKENS)
                .returnThinking(thinking)
                .timeout(Duration.ofSeconds(120));
        if (thinking) {
            builder.thinkingType("adaptive");
        }
        return builder.build();
    }

    private StreamingChatModel geminiStreamingChatModel(AppConfig.AiModelConnection connection, String thinkingMode) {
        boolean thinking = isThinkingEnabled(thinkingMode);
        GoogleAiGeminiStreamingChatModel.GoogleAiGeminiStreamingChatModelBuilder builder = GoogleAiGeminiStreamingChatModel.builder()
                .baseUrl(connection.baseUrl)
                .apiKey(connection.apiKey)
                .modelName(connection.model)
                .returnThinking(thinking)
                .timeout(Duration.ofSeconds(120));
        if (thinking) {
            builder.thinkingConfig(geminiThinkingConfig(thinkingMode));
        }
        return builder.build();
    }

    private GeminiThinkingConfig geminiThinkingConfig(String thinkingMode) {
        return GeminiThinkingConfig.builder()
                .includeThoughts(true)
                .thinkingLevel(geminiThinkingLevel(thinkingMode))
                .build();
    }

    private ChatRequest buildRequest(List<ChatMessage> messages, AppConfig.AiModelConnection connection) {
        return ChatRequest.builder()
                .messages(messages)
                .modelName(connection.model)
                .build();
    }

    private String openAiReasoningEffort(String thinkingMode) {
        return switch (thinkingMode) {
            case "LOW" -> "low";
            case "HIGH" -> "high";
            default -> "medium";
        };
    }

    private GeminiThinkingConfig.GeminiThinkingLevel geminiThinkingLevel(String mode) {
        return switch (mode) {
            case "LOW" -> GeminiThinkingConfig.GeminiThinkingLevel.LOW;
            case "HIGH" -> GeminiThinkingConfig.GeminiThinkingLevel.HIGH;
            default -> GeminiThinkingConfig.GeminiThinkingLevel.MEDIUM;
        };
    }

    private boolean isThinkingEnabled(String thinkingMode) {
        return thinkingMode != null && !"OFF".equals(thinkingMode);
    }

    private List<ChatMessage> toLangChainMessages(AiConversation conversation, AiChatMessage pendingUserMessage,
                                                   String connId, boolean includeImages) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(AiContextService.getInstance().buildSystemPrompt(connId)));
        if (conversation != null && conversation.messages != null) {
            for (AiChatMessage message : conversation.messages) {
                if (message == null || message.content == null || message.content.isBlank()) {
                    continue;
                }
                if (pendingUserMessage != null && pendingUserMessage.id != null && pendingUserMessage.id.equals(message.id)) {
                    continue;
                }
                if ("assistant".equalsIgnoreCase(message.role)) {
                    messages.add(AiMessage.from(message.content));
                } else {
                    messages.add(toUserMessage(message, includeImages));
                }
            }
        }
        if (pendingUserMessage != null) {
            messages.add(toUserMessage(pendingUserMessage, includeImages));
        }
        return messages;
    }

    private UserMessage toUserMessage(AiChatMessage message, boolean includeImages) {
        List<dev.langchain4j.data.message.Content> contents = new ArrayList<>();
        if (message.content != null && !message.content.isBlank()) {
            contents.add(TextContent.from(message.content));
        }
        if (includeImages && message.images != null) {
            for (AiImageAttachment image : message.images) {
                if (image == null || image.base64Data == null || image.base64Data.isBlank()) {
                    continue;
                }
                contents.add(ImageContent.from(image.base64Data, image.mimeType));
            }
        }
        if (contents.isEmpty()) {
            contents.add(TextContent.from(""));
        }
        return UserMessage.from(contents);
    }

    private void validateSettings(AppConfig.AiModelConnection connection) {
        if (!settings.isAiEnabled()) {
            throw new IllegalStateException("请先在设置中启用 AI 助手");
        }
        if (connection == null) {
            throw new IllegalStateException("请先配置模型连接");
        }
        if (connection.baseUrl == null || connection.baseUrl.isBlank()) {
            throw new IllegalStateException("请先配置大模型 API Base URL");
        }
        if (connection.apiKey == null || connection.apiKey.isBlank()) {
            throw new IllegalStateException("请先配置大模型 API Key");
        }
        if (connection.model == null || connection.model.isBlank()) {
            throw new IllegalStateException("请先选择模型");
        }
    }

    private void validateImageInput(AppConfig.AiModelConnection connection, AiChatMessage userMessage) {
        if (!connection.imageInputSupported
                && userMessage != null
                && userMessage.images != null
                && !userMessage.images.isEmpty()) {
            throw new IllegalStateException("当前模型连接未启用图片输入");
        }
    }

    private String safeFormat(AppConfig.AiModelConnection connection) {
        return connection == null || connection.apiFormat == null ? "" : connection.apiFormat;
    }

    public AiChatMessage newUserMessage(String text, String model, List<AiImageAttachment> images) {
        return new AiChatMessage(UUID.randomUUID().toString(), "user", text, "", model, images);
    }

    public AiChatMessage newAssistantMessage(String text, String thinking, String model) {
        return new AiChatMessage(UUID.randomUUID().toString(), "assistant", text, thinking, model, List.of());
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    public interface ResponseCallback {
        void onPartial(String text);

        void onThinking(String text);

        void onComplete(String text, String thinking);

        void onError(Throwable error);
    }

    public static final class ChatRequestHandle {
        private final AtomicBoolean cancelled;
        private final CompletableFuture<Void> completion;

        private ChatRequestHandle(AtomicBoolean cancelled, CompletableFuture<Void> completion) {
            this.cancelled = cancelled;
            this.completion = completion;
        }

        public void cancel() {
            cancelled.set(true);
            completion.cancel(true);
        }
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(@NotNull Runnable runnable) {
            Thread thread = new Thread(runnable, "ai-chat-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
