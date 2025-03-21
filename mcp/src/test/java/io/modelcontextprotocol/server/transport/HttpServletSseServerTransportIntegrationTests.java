/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.server.transport;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class HttpServletSseServerTransportIntegrationTests {

	private static final int PORT = 8184;

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private HttpServletSseServerTransport mcpServerTransport;

	McpClient.SyncSpec clientBuilder;

	private Tomcat tomcat;

	/**
	 * 设置嵌入式 Tomcat 服务器：
	 * 创建一个新的 Tomcat 实例
	 * 设置服务器端口（使用预定义的 PORT 变量）
	 * 设置临时目录作为基础目录
	 * 配置 MCP (Model Context Protocol) 服务器传输层：
	 * 创建 HttpServletSseServerTransport 实例，用于处理服务器端的 SSE (Server-Sent Events) 通信
	 * 使用 ObjectMapper 进行 JSON 序列化/反序列化
	 * 设置 Servlet 环境：
	 * 创建并配置一个 Servlet Wrapper
	 * 设置异步支持（setAsyncSupported(true)）
	 * 配置 URL 映射，使所有请求（/*）都由这个 Servlet 处理
	 * 启动 Tomcat 服务器：
	 * 设置异步超时时间为 3000 毫秒
	 * 启动服务器并验证服务器状态
	 * 如果启动失败，抛出运行时异常
	 * 初始化测试客户端：
	 * 创建一个 MCP 客户端，使用 HttpClientSseClientTransport 配置为与本地服务器通信
	 * 这个方法的主要目的是为集成测试创建一个完整的测试环境，包括服务器端和客户端的配置。它确保每个测试用例都在一个干净的、可控的环境中运行。这是一个典型的集成测试设置，用于测试 MCP（Model Context Protocol）的 HTTP/SSE 传输实现。
	 * */
	@BeforeEach
	public void before() {
		tomcat = new Tomcat();
		tomcat.setPort(PORT);

		String baseDir = System.getProperty("java.io.tmpdir");
		tomcat.setBaseDir(baseDir);

		Context context = tomcat.addContext("", baseDir);

		// Create and configure the transport
		mcpServerTransport = new HttpServletSseServerTransport(new ObjectMapper(), MESSAGE_ENDPOINT);

		// Add transport servlet to Tomcat
		org.apache.catalina.Wrapper wrapper = context.createWrapper();
		wrapper.setName("mcpServlet");
		wrapper.setServlet(mcpServerTransport);
		wrapper.setLoadOnStartup(1);
		wrapper.setAsyncSupported(true);
		context.addChild(wrapper);
		context.addServletMappingDecoded("/*", "mcpServlet");

		try {
			var connector = tomcat.getConnector();
			connector.setAsyncTimeout(3000);
			tomcat.start();
			assertThat(tomcat.getServer().getState() == LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		this.clientBuilder = McpClient.sync(new HttpClientSseClientTransport("http://localhost:" + PORT));
	}

	@AfterEach
	public void after() {
		if (mcpServerTransport != null) {
			mcpServerTransport.closeGracefully().block();
		}
		if (tomcat != null) {
			try {
				tomcat.stop();
				tomcat.destroy();
			}
			catch (LifecycleException e) {
				throw new RuntimeException("Failed to stop Tomcat", e);
			}
		}
	}

	@Test
	void testCreateMessageWithoutInitialization() {
		var mcpAsyncServer = McpServer.async(mcpServerTransport).serverInfo("test-server", "1.0.0").build();

		var messages = List
			.of(new McpSchema.SamplingMessage(McpSchema.Role.USER, new McpSchema.TextContent("Test message")));
		var modelPrefs = new McpSchema.ModelPreferences(List.of(), 1.0, 1.0, 1.0);

		var request = new McpSchema.CreateMessageRequest(messages, modelPrefs, null,
				McpSchema.CreateMessageRequest.ContextInclusionStrategy.NONE, null, 100, List.of(), Map.of());

		StepVerifier.create(mcpAsyncServer.createMessage(request)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class)
				.hasMessage("Client must be initialized. Call the initialize method first!");
		});
	}

	@Test
	void testCreateMessageWithoutSamplingCapabilities() {
		var mcpAsyncServer = McpServer.async(mcpServerTransport).serverInfo("test-server", "1.0.0").build();

		var client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0")).build();

		InitializeResult initResult = client.initialize();
		assertThat(initResult).isNotNull();

		var messages = List
			.of(new McpSchema.SamplingMessage(McpSchema.Role.USER, new McpSchema.TextContent("Test message")));
		var modelPrefs = new McpSchema.ModelPreferences(List.of(), 1.0, 1.0, 1.0);

		var request = new McpSchema.CreateMessageRequest(messages, modelPrefs, null,
				McpSchema.CreateMessageRequest.ContextInclusionStrategy.NONE, null, 100, List.of(), Map.of());

		StepVerifier.create(mcpAsyncServer.createMessage(request)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class)
				.hasMessage("Client must be configured with sampling capabilities");
		});
	}

	@Test
	void testCreateMessageSuccess() {
		var mcpAsyncServer = McpServer.async(mcpServerTransport).serverInfo("test-server", "1.0.0").build();

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.messages()).hasSize(1);
			assertThat(request.messages().get(0).content()).isInstanceOf(McpSchema.TextContent.class);

			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		var client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build();

		InitializeResult initResult = client.initialize();
		assertThat(initResult).isNotNull();

		var messages = List
			.of(new McpSchema.SamplingMessage(McpSchema.Role.USER, new McpSchema.TextContent("Test message")));
		var modelPrefs = new McpSchema.ModelPreferences(List.of(), 1.0, 1.0, 1.0);

		var request = new McpSchema.CreateMessageRequest(messages, modelPrefs, null,
				McpSchema.CreateMessageRequest.ContextInclusionStrategy.NONE, null, 100, List.of(), Map.of());

		StepVerifier.create(mcpAsyncServer.createMessage(request)).consumeNextWith(result -> {
			assertThat(result).isNotNull();
			assertThat(result.role()).isEqualTo(Role.USER);
			assertThat(result.content()).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) result.content()).text()).isEqualTo("Test message");
			assertThat(result.model()).isEqualTo("MockModelName");
			assertThat(result.stopReason()).isEqualTo(CreateMessageResult.StopReason.STOP_SEQUENCE);
		}).verifyComplete();
	}

	@Test
	void testRootsSuccess() {
		List<Root> roots = List.of(new Root("uri1://", "root1"), new Root("uri2://", "root2"));

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();
		var mcpServer = McpServer.sync(mcpServerTransport)
			.rootsChangeConsumer(rootsUpdate -> rootsRef.set(rootsUpdate))
			.build();

		var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		assertThat(rootsRef.get()).isNull();

		assertThat(mcpServer.listRoots().roots()).containsAll(roots);

		mcpClient.rootsListChangedNotification();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(roots);
		});

		mcpClient.close();
		mcpServer.close();
	}

	String emptyJsonSchema = """
			{
			    "$schema": "http://json-schema.org/draft-07/schema#",
			    "type": "object",
			    "properties": {}
			}
			""";

	/**
	 * 验证了 MCP 服务器的以下功能：
	 * 工具的注册和配置
	 * 服务器初始化
	 * 工具列表获取
	 * 工具调用
	 * 响应处理
	 * */
	@Test
	void testToolCallSuccess() {
		// 创建了一个预期的工具调用响应对象，包含一个文本内容 "CALL RESPONSE"。
		var callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")), null);
		/*
		 * 注册了一个名为 "tool1" 的同步工具，这个工具会：
		 * 发送 HTTP GET 请求到 MCP 规范的 README 文件
		 * 验证响应不为空
		 * 返回预先定义的 callResponse
		 * */
		McpServerFeatures.SyncToolRegistration tool1 = new McpServerFeatures.SyncToolRegistration(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), request -> {
					String response = RestClient.create()
						.get()
						.uri("https://github.com/modelcontextprotocol/specification/blob/main/README.md")
						.retrieve()
						.body(String.class);
					assertThat(response).isNotBlank();
					return callResponse;
				});
		// 创建了一个 MCP 服务器，启用了工具功能，并注册了上面定义的 tool1。
		var mcpServer = McpServer.sync(mcpServerTransport)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		// 创建客户端并进行初始化，确保初始化成功。
		var mcpClient = clientBuilder.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		// 验证客户端能够正确获取到已注册的工具列表，并包含 tool1。
		assertThat(mcpClient.listTools().tools()).contains(tool1.tool());

		/*
		 * 测试工具调用功能：
		 * 调用 tool1
		 * 验证返回的响应不为空
		 * 验证响应与预期的 callResponse 相匹配
		 * */
		CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));

		assertThat(response).isNotNull();
		assertThat(response).isEqualTo(callResponse);

		mcpClient.close();
		mcpServer.close();
	}

	@Test
	void testToolListChangeHandlingSuccess() {
		// 创建了一个预期的工具调用响应对象，包含一个文本内容 "CALL RESPONSE"。
		var callResponse = new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CALL RESPONSE")), null);
		// 创建了一个测试用的工具 tool1，它包含基本信息（ID、描述）和一个简单的处理函数
		// 这个工具的处理函数会发起一个 HTTP GET 请求到 GitHub，并返回一个预定义的响应
		McpServerFeatures.SyncToolRegistration tool1 = new McpServerFeatures.SyncToolRegistration(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), request -> {
					String response = RestClient.create()
						.get()
						.uri("https://github.com/modelcontextprotocol/specification/blob/main/README.md")
						.retrieve()
						.body(String.class);
					assertThat(response).isNotBlank();
					return callResponse;
				});
		// 创建了一个 MCP 服务器实例，启用了工具功能，并注册了 tool1
		var mcpServer = McpServer.sync(mcpServerTransport)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		// 使用 AtomicReference 来跟踪工具列表的变化
		AtomicReference<List<Tool>> toolsRef = new AtomicReference<>();

		// 创建了一个 MCP 客户端，并配置了工具变更的监听器（toolsChangeConsumer）
		var mcpClient = clientBuilder.toolsChangeConsumer(toolsUpdate -> {
			String response = RestClient.create()
				.get()
				.uri("https://github.com/modelcontextprotocol/specification/blob/main/README.md")
				.retrieve()
				.body(String.class);
			assertThat(response).isNotBlank();
			toolsRef.set(toolsUpdate);
		}).build();
		// 验证客户端初始化成功
		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		// 确认初始状态下 toolsRef 为空
		assertThat(toolsRef.get()).isNull();
		// 验证客户端可以列出包含 tool1 的工具列表
		assertThat(mcpClient.listTools().tools()).contains(tool1.tool());
		// 服务器发送工具列表变更通知
		mcpServer.notifyToolsListChanged();

		// 验证客户端能收到包含 tool1 的更新
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
			assertThat(toolsRef.get()).containsAll(List.of(tool1.tool()));
		});
		// 从服务器移除 tool1
		mcpServer.removeTool("tool1");

		// 验证客户端收到空工具列表的更新
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(toolsRef.get()).isEmpty();
		});

		// 创建并添加新工具 tool2
		McpServerFeatures.SyncToolRegistration tool2 = new McpServerFeatures.SyncToolRegistration(
				new McpSchema.Tool("tool2", "tool2 description", emptyJsonSchema), request -> callResponse);
		mcpServer.addTool(tool2);

		// 验证客户端收到包含 tool2 的更新
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(toolsRef.get()).containsAll(List.of(tool2.tool()));
		});

		mcpClient.close();
		mcpServer.close();
	}

	@Test
	void testInitialize() {
		var mcpServer = McpServer.sync(mcpServerTransport).build();
		var mcpClient = clientBuilder.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		mcpClient.close();
		mcpServer.close();
	}

}
