package win.youhoo.moss;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** JDK 17. All HTTP fixtures use loopback. --official also downloads the pinned release. */
public final class AsrModelFileTest {
    private static final AsrModelFile.Progress QUIET = (bytes, verifying) -> {};
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private interface Job { void run() throws IOException; }
    private static void expectFailure(Job job) throws IOException {
        try { job.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected a failed transfer");
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--official")) {
            File model = new File(args[1], "moss-q8.gguf");
            AsrModelFile.download(model, QUIET);
            System.out.println("Official file verified and activated, bytes=" + model.length());
            return;
        }
        byte[] data = new byte[800000];
        new Random(17).nextBytes(data);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        AtomicReference<String> lastRange = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String range = ex.getRequestHeaders().getFirst("Range");
            lastRange.set(range);
            try {
                if (path.equals("/redirect")) {
                    ex.getResponseHeaders().set("Location", "/model");
                    ex.sendResponseHeaders(302, -1);
                    return;
                }
                int from = range == null || path.equals("/ignore") ? 0 :
                        Integer.parseInt(range.substring(6, range.length() - 1));
                int end = path.equals("/chunks") ? Math.min(from + 120000, data.length) : data.length;
                boolean partial = from > 0 || path.equals("/chunks");
                if (partial) ex.getResponseHeaders().set("Content-Range", "bytes " +
                        (path.equals("/invalid") ? from + 1 : from) + "-" + (end - 1) + "/" + data.length);
                int bytes = end - from;
                if (path.equals("/wrong-size")) bytes--;
                ex.sendResponseHeaders(partial ? 206 : 200, bytes);
                ex.getResponseBody().write(data, from, bytes);
            } finally { ex.close(); }
        });
        server.start();
        Path temp = Files.createTempDirectory("yaoshi-download-");
        File model = temp.resolve("model.gguf").toFile();
        Path part = AsrModelFile.partial(model).toPath();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            for (String route : List.of("/redirect", "/ignore", "/chunks")) {
                Files.write(part, Arrays.copyOf(data, 45213));
                AsrModelFile.download(new URL(base + route), model, data.length, sha, QUIET);
                check(Arrays.equals(data, Files.readAllBytes(model.toPath())), "Bad data after " + route);
                check(!Files.exists(part), "Stage was not promoted");
                if (route.equals("/redirect")) check("bytes=45213-".equals(lastRange.get()), "Lost range on redirect");
            }
            URL url = new URL(base + "/model");
            byte[] old = {7, 8, 9};
            Files.write(model.toPath(), old);
            expectFailure(() -> AsrModelFile.download(url, model, data.length, sha, (n, verify) -> {
                if (!verify && n > 0) throw new InterruptedIOException();
            }));
            long stoppedAt = Files.size(part);
            check(stoppedAt > 0 && stoppedAt < data.length, "Missing resumable bytes");
            expectFailure(() -> AsrModelFile.download(new URL(base + "/invalid"), model, data.length, sha, QUIET));
            check(Files.size(part) == stoppedAt, "Bad range damaged the partial file");
            Files.delete(part);
            expectFailure(() -> AsrModelFile.download(new URL(base + "/wrong-size"), model, data.length, sha, QUIET));
            expectFailure(() -> AsrModelFile.download(url, model, data.length, "0".repeat(64), QUIET));
            check(!Files.exists(part) && Arrays.equals(Files.readAllBytes(model.toPath()), old), "Failed verification replaced the active file");
            Files.write(part, data);
            AsrModelFile.download(new URL(base + "/unreachable"), model, data.length, sha, QUIET);
            check(Arrays.equals(data, Files.readAllBytes(model.toPath())), "Cannot activate completed partial");
            System.out.println("PASS redirect, byte range, restart, chunked ranges, interruption, invalid range, size, digest, atomic activation");
        } finally {
            server.stop(0);
            Files.deleteIfExists(part);
            Files.deleteIfExists(model.toPath());
            Files.delete(temp);
        }
    }
}
