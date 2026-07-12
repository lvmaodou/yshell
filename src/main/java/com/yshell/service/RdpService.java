package com.yshell.service;

import com.yshell.model.ConnInfo;
import com.yshell.model.ProxyInfo;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RdpService {

    private static final int CONNECT_TIMEOUT_MS = 15_000;

    private RdpService() {
    }

    public static void connect(ConnInfo connInfo) throws IOException {
        validate(connInfo);
        if (isWindows()) {
            connectWithMstsc(connInfo);
        } else {
            connectWithFreeRdp(connInfo);
        }
    }

    private static void validate(ConnInfo connInfo) throws IOException {
        if (connInfo == null) {
            throw new IOException("连接信息为空");
        }
        if (isBlank(connInfo.getHost())) {
            throw new IOException("RDP 主机不能为空");
        }
    }

    private static void connectWithMstsc(ConnInfo connInfo) throws IOException {
        RdpEndpoint endpoint = createEndpoint(connInfo);
        boolean started = false;
        try {
            if (!isBlank(connInfo.getUserName()) && !isBlank(connInfo.getPassword())) {
                storeWindowsCredential(connInfo, endpoint.host());
            }

            Path rdpFile = Files.createTempFile("yshell-rdp-", ".rdp");
            Files.writeString(rdpFile, buildRdpFile(connInfo, endpoint), StandardCharsets.UTF_16LE);
            rdpFile.toFile().deleteOnExit();

            List<String> command = new ArrayList<>();
            command.add("mstsc.exe");
            command.add(rdpFile.toString());
            if (connInfo.isFullscreen()) {
                command.add("/f");
            }
            Process process = new ProcessBuilder(command).start();
            started = true;
            endpoint.closeWhenProcessExits(process);
        } finally {
            if (!started) {
                endpoint.close();
            }
        }
    }

    private static String buildRdpFile(ConnInfo connInfo, RdpEndpoint endpoint) {
        StringBuilder builder = new StringBuilder();
        builder.append("full address:s:")
                .append(endpoint.host())
                .append(":")
                .append(endpoint.port())
                .append("\r\n");
        if (!isBlank(connInfo.getUserName())) {
            builder.append("username:s:").append(connInfo.getUserName()).append("\r\n");
        }
        builder.append("screen mode id:i:")
                .append(connInfo.isFullscreen() ? "2" : "1")
                .append("\r\n");
        int width = connInfo.getWidth() > 0 ? connInfo.getWidth() : 1280;
        int height = connInfo.getHeight() > 0 ? connInfo.getHeight() : 720;
        if (!connInfo.isFullscreen()) {
            builder.append("desktopwidth:i:").append(width).append("\r\n");
            builder.append("desktopheight:i:").append(height).append("\r\n");
            builder.append("winposstr:s:0,1,80,80,")
                    .append(80 + width)
                    .append(",")
                    .append(120 + height)
                    .append("\r\n");
        }
        builder.append("use multimon:i:0\r\n");
        builder.append("span monitors:i:0\r\n");
        builder.append("redirectdrives:i:")
                .append(connInfo.isDriveStoreDirect() ? "1" : "0")
                .append("\r\n");
        builder.append("authentication level:i:2\r\n");
        builder.append("enablecredsspsupport:i:1\r\n");
        builder.append("prompt for credentials:i:")
                .append(isBlank(connInfo.getPassword()) ? "1" : "0")
                .append("\r\n");
        builder.append("promptcredentialonce:i:1\r\n");
        builder.append("smart sizing:i:1\r\n");
        builder.append("dynamic resolution:i:0\r\n");
        builder.append("desktopscalefactor:i:100\r\n");
        builder.append("devicescalefactor:i:100\r\n");
        if (connInfo.isAccelerate()) {
            appendWeakNetworkRdpOptions(builder);
        } else {
            builder.append("session bpp:i:32\r\n");
        }
        return builder.toString();
    }

    private static void appendWeakNetworkRdpOptions(StringBuilder builder) {
        builder.append("compression:i:1\r\n");
        builder.append("networkautodetect:i:1\r\n");
        builder.append("bandwidthautodetect:i:1\r\n");
        builder.append("connection type:i:2\r\n");
        builder.append("session bpp:i:16\r\n");
        builder.append("disable wallpaper:i:1\r\n");
        builder.append("allow font smoothing:i:0\r\n");
        builder.append("disable full window drag:i:1\r\n");
        builder.append("disable menu anims:i:1\r\n");
        builder.append("disable themes:i:1\r\n");
        builder.append("audiomode:i:2\r\n");
        builder.append("redirectclipboard:i:1\r\n");
    }

    private static void storeWindowsCredential(ConnInfo connInfo, String targetHost) throws IOException {
        List<String> command = List.of(
                "cmdkey.exe",
                "/generic:TERMSRV/" + targetHost,
                "/user:" + connInfo.getUserName(),
                "/pass:" + connInfo.getPassword()
        );
        Process process = new ProcessBuilder(command).start();
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("写入 Windows RDP 凭据失败");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("写入 Windows RDP 凭据被中断", e);
        }
    }

    private static void connectWithFreeRdp(ConnInfo connInfo) throws IOException {
        RdpEndpoint endpoint = createEndpoint(connInfo);
        boolean started = false;
        try {
            List<String> command = new ArrayList<>();
            command.add(resolveFreeRdpCommand());
            command.add("/v:" + endpoint.host() + ":" + endpoint.port());
            if (!isBlank(connInfo.getUserName())) {
                command.add("/u:" + connInfo.getUserName());
            }
            if (!isBlank(connInfo.getPassword())) {
                command.add("/p:" + connInfo.getPassword());
            }
            if (connInfo.isFullscreen()) {
                command.add("/f");
            } else {
                int width = connInfo.getWidth() > 0 ? connInfo.getWidth() : 1280;
                int height = connInfo.getHeight() > 0 ? connInfo.getHeight() : 720;
                command.add("/size:" + width + "x" + height);
            }
            if (connInfo.isDriveStoreDirect()) {
                command.add("/drive:home," + System.getProperty("user.home"));
            }
            if (connInfo.isAccelerate()) {
                appendWeakNetworkFreeRdpOptions(command);
            }
            Process process = new ProcessBuilder(command).start();
            started = true;
            endpoint.closeWhenProcessExits(process);
        } finally {
            if (!started) {
                endpoint.close();
            }
        }
    }

    private static void appendWeakNetworkFreeRdpOptions(List<String> command) {
        command.add("/network:auto");
        command.add("/bpp:16");
        command.add("+compression");
        command.add("-wallpaper");
        command.add("-themes");
        command.add("-menu-anims");
        command.add("-window-drag");
        command.add("/sound:off");
        command.add("+async-update");
        command.add("+async-input");
        command.add("/gfx");
    }

    private static RdpEndpoint createEndpoint(ConnInfo connInfo) throws IOException {
        ProxyInfo proxyInfo = selectedProxy(connInfo);
        int targetPort = portOrDefault(connInfo);
        if (proxyInfo == null) {
            return new RdpEndpoint(connInfo.getHost(), targetPort, null);
        }
        RdpProxyTunnel tunnel = RdpProxyTunnel.start(proxyInfo, connInfo.getHost(), targetPort);
        return new RdpEndpoint("127.0.0.1", tunnel.localPort(), tunnel);
    }

    private static ProxyInfo selectedProxy(ConnInfo connInfo) throws IOException {
        String proxyId = connInfo.getProxyId();
        if (isBlank(proxyId) || "0".equals(proxyId)) {
            return null;
        }
        return ProxyRepository.getInstance().load().stream()
                .filter(proxy -> proxyId.equals(proxy.getId()))
                .filter(proxy -> !isBlank(proxy.getHost()) && proxy.getPort() > 0)
                .findFirst()
                .orElseThrow(() -> new IOException("RDP 代理不存在或配置无效"));
    }

    private static String resolveFreeRdpCommand() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return "xfreerdp";
        }
        String sessionType = System.getenv("XDG_SESSION_TYPE");
        if ("wayland".equalsIgnoreCase(sessionType)) {
            return "wlfreerdp";
        }
        return "xfreerdp";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static int portOrDefault(ConnInfo connInfo) {
        return connInfo.getPort() > 0 ? connInfo.getPort() : 3389;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record RdpEndpoint(String host, int port, RdpProxyTunnel tunnel) implements AutoCloseable {
        void closeWhenProcessExits(Process process) {
            if (tunnel == null) {
                return;
            }
            Thread closer = new Thread(() -> {
                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    close();
                }
            }, "RdpProxyTunnelCloser-" + port);
            closer.setDaemon(true);
            closer.start();
        }

        @Override
        public void close() {
            if (tunnel != null) {
                tunnel.close();
            }
        }
    }

    private static final class RdpProxyTunnel implements AutoCloseable {
        private final ProxyInfo proxyInfo;
        private final String targetHost;
        private final int targetPort;
        private final ServerSocket serverSocket;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private RdpProxyTunnel(ProxyInfo proxyInfo, String targetHost, int targetPort, ServerSocket serverSocket) {
            this.proxyInfo = proxyInfo;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.serverSocket = serverSocket;
        }

        static RdpProxyTunnel start(ProxyInfo proxyInfo, String targetHost, int targetPort) throws IOException {
            ServerSocket serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            RdpProxyTunnel tunnel = new RdpProxyTunnel(proxyInfo, targetHost, targetPort, serverSocket);
            tunnel.startAcceptLoop();
            return tunnel;
        }

        int localPort() {
            return serverSocket.getLocalPort();
        }

        private void startAcceptLoop() {
            Thread thread = new Thread(this::acceptLoop, "RdpProxyTunnel-" + localPort());
            thread.setDaemon(true);
            thread.start();
        }

        private void acceptLoop() {
            while (open.get()) {
                try {
                    Socket client = serverSocket.accept();
                    client.setTcpNoDelay(true);
                    Thread handler = new Thread(() -> handleClient(client), "RdpProxyTunnelClient-" + localPort());
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException e) {
                    if (open.get()) {
                        close();
                    }
                }
            }
        }

        private void handleClient(Socket client) {
            Socket remote;
            try {
                remote = openProxySocket();
                startPipe(client, remote, "RdpProxyTunnelUp-" + localPort());
                startPipe(remote, client, "RdpProxyTunnelDown-" + localPort());
            } catch (IOException e) {
                closeQuietly(client);
            }
        }

        private Socket openProxySocket() throws IOException {
            String type = proxyInfo.getType();
            if ("http".equalsIgnoreCase(type)) {
                return openHttpConnectSocket();
            }
            return openSocks5Socket();
        }

        private Socket openHttpConnectSocket() throws IOException {
            Socket socket = connectToProxy();
            try {
                String target = targetHost + ":" + targetPort;
                StringBuilder request = new StringBuilder()
                        .append("CONNECT ").append(target).append(" HTTP/1.1\r\n")
                        .append("Host: ").append(target).append("\r\n")
                        .append("Proxy-Connection: Keep-Alive\r\n");
                if (!isBlank(proxyInfo.getUsername())) {
                    String rawCredentials = proxyInfo.getUsername() + ":" + (proxyInfo.getPassword() != null ? proxyInfo.getPassword() : "");
                    String credentials = Base64.getEncoder()
                            .encodeToString(rawCredentials.getBytes(StandardCharsets.UTF_8));
                    request.append("Proxy-Authorization: Basic ").append(credentials).append("\r\n");
                }
                request.append("\r\n");
                OutputStream out = socket.getOutputStream();
                out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
                out.flush();

                String response = readHttpHeader(socket.getInputStream());
                int status = parseHttpStatus(response);
                if (status < 200 || status >= 300) {
                    throw new IOException("HTTP 代理 CONNECT 失败: " + status);
                }
                return socket;
            } catch (IOException e) {
                closeQuietly(socket);
                throw e;
            }
        }

        private Socket openSocks5Socket() throws IOException {
            Socket socket = connectToProxy();
            try {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                byte authMethod = isBlank(proxyInfo.getUsername()) ? (byte) 0x00 : (byte) 0x02;
                if (authMethod == 0x00) {
                    out.write(new byte[]{0x05, 0x01, 0x00});
                } else {
                    out.write(new byte[]{0x05, 0x02, 0x00, 0x02});
                }
                out.flush();
                byte[] greeting = readExact(in, 2);
                if (greeting[0] != 0x05 || greeting[1] == (byte) 0xFF) {
                    throw new IOException("SOCKS5 代理不支持可用认证方式");
                }
                if (greeting[1] == 0x02) {
                    writeSocks5Credentials(out);
                    byte[] auth = readExact(in, 2);
                    if (auth[1] != 0x00) {
                        throw new IOException("SOCKS5 代理认证失败");
                    }
                } else if (greeting[1] != 0x00) {
                    throw new IOException("SOCKS5 代理选择了不支持的认证方式");
                }

                out.write(new byte[]{0x05, 0x01, 0x00});
                writeSocks5Address(out, targetHost);
                out.write((targetPort >>> 8) & 0xFF);
                out.write(targetPort & 0xFF);
                out.flush();

                byte[] reply = readExact(in, 4);
                if (reply[1] != 0x00) {
                    throw new IOException("SOCKS5 代理连接目标失败: " + (reply[1] & 0xFF));
                }
                skipSocks5BindAddress(in, reply[3]);
                return socket;
            } catch (IOException e) {
                closeQuietly(socket);
                throw e;
            }
        }

        private Socket connectToProxy() throws IOException {
            Socket socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(proxyInfo.getHost(), proxyInfo.getPort()), CONNECT_TIMEOUT_MS);
            return socket;
        }

        private void writeSocks5Credentials(OutputStream out) throws IOException {
            byte[] user = proxyInfo.getUsername().getBytes(StandardCharsets.UTF_8);
            byte[] password = proxyInfo.getPassword() != null
                    ? proxyInfo.getPassword().getBytes(StandardCharsets.UTF_8)
                    : new byte[0];
            if (user.length > 255 || password.length > 255) {
                throw new IOException("SOCKS5 用户名或密码过长");
            }
            out.write(0x01);
            out.write(user.length);
            out.write(user);
            out.write(password.length);
            out.write(password);
            out.flush();
        }

        private void writeSocks5Address(OutputStream out, String host) throws IOException {
            if (isIpv4Literal(host)) {
                out.write(0x01);
                out.write(InetAddress.getByName(host).getAddress());
                return;
            }
            if (host.contains(":")) {
                out.write(0x04);
                out.write(InetAddress.getByName(host).getAddress());
                return;
            }
            byte[] domain = host.getBytes(StandardCharsets.UTF_8);
            if (domain.length > 255) {
                throw new IOException("SOCKS5 目标主机名过长");
            }
            out.write(0x03);
            out.write(domain.length);
            out.write(domain);
        }

        private void skipSocks5BindAddress(InputStream in, byte addressType) throws IOException {
            int length = switch (addressType) {
                case 0x01 -> 4;
                case 0x03 -> in.read();
                case 0x04 -> 16;
                default -> throw new IOException("SOCKS5 响应地址类型无效");
            };
            if (length < 0) {
                throw new EOFException("SOCKS5 响应不完整");
            }
            readExact(in, length + 2);
        }

        private static boolean isIpv4Literal(String value) {
            return value != null && value.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        }

        private void startPipe(Socket inputSocket, Socket outputSocket, String threadName) {
            Thread thread = new Thread(() -> {
                try {
                    inputSocket.getInputStream().transferTo(outputSocket.getOutputStream());
                } catch (IOException ignored) {
                    // Closing either side is the normal way an RDP session ends.
                } finally {
                    closeQuietly(inputSocket);
                    closeQuietly(outputSocket);
                }
            }, threadName);
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void close() {
            if (!open.compareAndSet(true, false)) {
                return;
            }
            closeQuietly(serverSocket);
        }
    }

    private static String readHttpHeader(InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        int count = 0;
        while (count < buffer.length) {
            int value = in.read();
            if (value < 0) {
                throw new EOFException("HTTP 代理响应不完整");
            }
            buffer[count++] = (byte) value;
            if (count >= 4
                    && buffer[count - 4] == '\r'
                    && buffer[count - 3] == '\n'
                    && buffer[count - 2] == '\r'
                    && buffer[count - 1] == '\n') {
                return new String(buffer, 0, count, StandardCharsets.ISO_8859_1);
            }
        }
        throw new IOException("HTTP 代理响应头过长");
    }

    private static int parseHttpStatus(String response) throws IOException {
        int lineEnd = response.indexOf("\r\n");
        String statusLine = lineEnd >= 0 ? response.substring(0, lineEnd) : response;
        String[] parts = statusLine.split(" ");
        if (parts.length < 2) {
            throw new IOException("HTTP 代理响应无效");
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("HTTP 代理状态码无效", e);
        }
    }

    private static byte[] readExact(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new EOFException("代理响应不完整");
            }
            offset += read;
        }
        return buffer;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
