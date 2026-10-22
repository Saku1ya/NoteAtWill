package com.sakuya.notesatwill.config;

import com.sakuya.notesatwill.client.AiParseClient;
import com.sakuya.notesatwill.entity.DTO.ai.PythonParseRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证「每个 AI 客户端真的有各自的读超时」。
 *
 * <p><b>★ 为什么必须用行为测试，而不是断言属性</b>：
 * {@code SimpleClientHttpRequestFactory} <b>只有 setter 没有 getter</b>，
 * 无法读出"当前读超时是多少"。所以只能在<b>真实网络行为</b>上验证：
 * 起一个只接受连接、永不响应的 socket，然后看客户端多久放弃。
 *
 * <p><b>★ 为什么这件事值得专门测</b>：
 * 「按接口拆客户端」的<b>唯一目的</b>就是让超时不同。
 * 如果 {@link AiClientConfig#createClient} 把值传错、或所有客户端共用了同一个读超时，
 * 代码照样能编译、接口照样能调通，但首屏接口会继承 15 秒的超时 ——
 * 表现为"Python 一慢，首页就白屏十几秒"。这种 bug 只有超时真的触发时才看得出来。
 *
 * <p><b>★★ 本测试同时钉死了一个反直觉的事实（实测得出，勿按直觉改回去）</b>：
 * 读超时抛出的<b>不是</b> {@code ResourceAccessException}，而是
 * <pre>
 *   RestClientException("Error while extracting response ...")
 *     └─ SocketTimeoutException: Read timed out
 * </pre>
 * 原因是请求已经发出去了，是"读响应"阶段超时，Spring 在把响应转成 DTO 时失败，
 * 报的是"提取响应失败"。所以 {@code AiService} 只能按 cause 链判断超时 ——
 * 本测试把这个异常形状钉死，防止有人"优化"成按异常类判断而把超时错判成不可用。
 *
 * @author sakuya
 */
class AiClientTimeoutTest {

    /** 只接受连接、不读不写的服务端 —— 任何请求都不会得到响应 */
    private ServerSocket server;
    private final List<Socket> held = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void startSilentServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());

        Thread acceptor = new Thread(() -> {
            try {
                while (!server.isClosed()) {
                    // ★ 只接受、不响应。连接保持打开，于是客户端会一直等到读超时。
                    held.add(server.accept());
                }
            } catch (IOException ignored) {
                // server 关闭时 accept 会抛异常，属正常退出
            }
        }, "silent-ai-server");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stopSilentServer() {
        held.forEach(s -> {
            try {
                s.close();
            } catch (IOException ignored) {
                // 清理时忽略
            }
        });
        try {
            server.close();
        } catch (IOException ignored) {
            // 清理时忽略
        }
    }

    private AiParseClient client(long readTimeoutMs, String name) {
        AiProperties props = new AiProperties(
                "http://127.0.0.1:" + server.getLocalPort(),
                // 三个业务超时随便填，本测试直接传 readTimeoutMs 覆盖
                new AiProperties.Timeouts(15000, 3000, 30000, 15000));
        return AiClientConfig.createClient(props, readTimeoutMs, AiParseClient.class, name);
    }

    private PythonParseRequest request() {
        return new PythonParseRequest("测试", null, List.of(), List.of());
    }

    /** 调用客户端，返回"从发起到放弃"的耗时（毫秒） */
    private long timeUntilFailure(AiParseClient client) {
        long start = System.nanoTime();
        RestClientException e = assertThrows(RestClientException.class,
                () -> client.parseBill(request()),
                "对端永不响应，必须抛 RestClientException");
        long ms = (System.nanoTime() - start) / 1_000_000;

        // ★ 必须是"超时"导致的失败，而不是别的错误
        assertTrue(hasTimeoutCause(e),
                "★ cause 链里必须有 SocketTimeoutException（说明是读超时），实际："
                        + describe(e));
        return ms;
    }

    private boolean hasTimeoutCause(Throwable e) {
        Throwable c = e;
        while (c != null) {
            if (c instanceof SocketTimeoutException) {
                return true;
            }
            c = c.getCause();
        }
        return false;
    }

    private String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable c = e;
        int depth = 0;
        while (c != null && depth < 5) {
            sb.append(" <- ").append(c.getClass().getSimpleName());
            c = c.getCause();
            depth++;
        }
        return e.getClass().getSimpleName() + sb;
    }

    @Test
    void shortTimeoutClientGivesUpQuickly() {
        System.out.println("\n===== 读超时 250ms 的客户端应在约 250ms 放弃 =====");

        long elapsed = timeUntilFailure(client(250, "fast"));

        System.out.println("  实际放弃耗时: " + elapsed + "ms");
        assertTrue(elapsed < 1200,
                "★ 250ms 读超时的客户端不该等超过 1.2s，实际 " + elapsed + "ms");
        System.out.println("  ✓ 快速失败，符合 3 秒级首屏接口的需求");
    }

    @Test
    void longTimeoutClientWaitsLonger() {
        System.out.println("\n===== 读超时 1200ms 的客户端应等待更久 =====");

        long elapsed = timeUntilFailure(client(1200, "slow"));

        System.out.println("  实际放弃耗时: " + elapsed + "ms");
        assertTrue(elapsed >= 1000,
                "★ 1200ms 读超时的客户端至少要等 1s，实际 " + elapsed + "ms —— "
                        + "说明超时没被正确应用");
        System.out.println("  ✓ 等待时间与配置一致");
    }

    @Test
    void differentTimeoutsProduceDifferentBehaviour() {
        System.out.println("\n===== ★★ 两个不同超时的客户端，行为必须明显不同 =====");

        long fast = timeUntilFailure(client(250, "fast"));
        long slow = timeUntilFailure(client(1200, "slow"));

        System.out.printf("  fast(250ms) 放弃于 %dms，slow(1200ms) 放弃于 %dms%n", fast, slow);

        assertTrue(slow > fast * 2,
                "★ 读超时 4.8 倍的客户端，放弃耗时理应明显更长；"
                        + "若两者接近，说明超时值没被真正应用。fast=" + fast + ", slow=" + slow);
        System.out.println("  ✓ 超时确实按客户端分别生效 —— 这是本次拆分的核心目标");
    }

    @Test
    void readTimeoutSurfacesAsRestClientExceptionNotResourceAccessException() {
        System.out.println("\n===== ★★ 钉死异常形状：读超时是 RestClientException，不是 ResourceAccessException =====");

        long start = System.nanoTime();
        RestClientException e = assertThrows(RestClientException.class,
                () -> client(250, "shape").parseBill(request()));
        long ms = (System.nanoTime() - start) / 1_000_000;

        System.out.println("  耗时 " + ms + "ms");
        System.out.println("  异常类型: " + e.getClass().getName());
        System.out.println("  异常消息: " + e.getMessage());
        System.out.println("  cause 链: " + describe(e));
        System.out.println("  instanceof ResourceAccessException: "
                + (e instanceof org.springframework.web.client.ResourceAccessException));

        // ★ 校验它是一个"普通 RestClientException"（而非其子类 ResourceAccessException）
        assertTrue(!(e instanceof org.springframework.web.client.ResourceAccessException),
                "★ 实测读超时不是 ResourceAccessException —— 若这里失败，"
                        + "说明 Spring 行为变了，AiService 的 cause 链判断需要重新评估");
        assertTrue(hasTimeoutCause(e), "★ 但 cause 链里必须有 SocketTimeoutException");
        System.out.println("  ✓ 形状已钉死：AiService 只能按 cause 链判断超时");
    }
}
