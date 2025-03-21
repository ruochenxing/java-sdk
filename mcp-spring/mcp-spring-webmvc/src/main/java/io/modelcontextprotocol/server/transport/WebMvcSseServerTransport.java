/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.function.ServerResponse.SseBuilder;

/**
 * 基于 Spring WebMVC 实现的服务器端 SSE (Server-Sent Events) 传输层，用于 Model Context Protocol (MCP) 的双向通信。
 * 该实现提供了同步 WebMVC 操作和响应式编程模式之间的桥梁，以保持与响应式传输接口的兼容性。
 *
 * <p>
 * 主要特性：
 * <ul>
 * <li>使用 HTTP POST 实现客户端到服务器的消息传输，使用 SSE 实现服务器到客户端的消息传输</li>
 * <li>通过唯一 ID 管理客户端会话，确保消息可靠传递</li>
 * <li>支持优雅关闭，包括适当的会话清理</li>
 * <li>通过配置的端点提供 JSON-RPC 消息处理</li>
 * <li>包含内置的错误处理和日志记录</li>
 * </ul>
 *
 * <p>
 * 传输层在两个主要端点上运行：
 * <ul>
 * <li>{@code /sse} - SSE 端点，客户端在此建立事件流连接</li>
 * <li>可配置的消息端点 - 客户端通过 HTTP POST 发送 JSON-RPC 消息</li>
 * </ul>
 *
 * <p>
 * 此实现使用 {@link ConcurrentHashMap} 以线程安全的方式管理多个客户端会话。
 * 每个客户端会话都被分配一个唯一 ID 并维护自己的 SSE 连接。
 *
 * @author Christian Tzolov
 * @author Alexandros Pappas
 * @see ServerMcpTransport
 * @see RouterFunction
 */
public class WebMvcSseServerTransport implements ServerMcpTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebMvcSseServerTransport.class);

	/**
	 * 通过 SSE 连接发送的 JSON-RPC 消息的事件类型。
	 */
	public static final String MESSAGE_EVENT_TYPE = "message";

	/**
	 * 发送消息端点 URI 给客户端的事件类型。
	 */
	public static final String ENDPOINT_EVENT_TYPE = "endpoint";

	/**
	 * MCP 传输规范指定的默认 SSE 端点路径。
	 */
	public static final String DEFAULT_SSE_ENDPOINT = "/sse";

	/**
	 * 用于 JSON 序列化/反序列化的 ObjectMapper 实例。
	 */
	private final ObjectMapper objectMapper;

	/**
	 * 客户端发送 JSON-RPC 消息的端点 URI。
	 */
	private final String messageEndpoint;

	/**
	 * SSE 连接的端点 URI。
	 */
	private final String sseEndpoint;

	/**
	 * 定义 HTTP 端点的路由函数。
	 */
	private final RouterFunction<ServerResponse> routerFunction;

	/**
	 * 活动客户端会话的映射，以会话 ID 为键。
	 */
	private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();

	/**
	 * 指示传输层是否正在关闭的标志。
	 */
	private volatile boolean isClosing = false;

	/**
	 * 处理传入的 JSON-RPC 消息并生成响应的函数。
	 */
	private Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> connectHandler;

	/**
	 * 构造一个新的 WebMvcSseServerTransport 实例。
	 * @param objectMapper 用于消息 JSON 序列化/反序列化的 ObjectMapper
	 * @param messageEndpoint 客户端应通过 HTTP POST 发送 JSON-RPC 消息的端点 URI。
	 * 此端点将通过 SSE 连接的初始端点事件通知给客户端。
	 * @throws IllegalArgumentException 如果 objectMapper 或 messageEndpoint 为 null
	 */
	public WebMvcSseServerTransport(ObjectMapper objectMapper, String messageEndpoint, String sseEndpoint) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.notNull(messageEndpoint, "Message endpoint must not be null");
		Assert.notNull(sseEndpoint, "SSE endpoint must not be null");

		this.objectMapper = objectMapper;
		this.messageEndpoint = messageEndpoint;
		this.sseEndpoint = sseEndpoint;
		this.routerFunction = RouterFunctions.route()
			.GET(this.sseEndpoint, this::handleSseConnection)
			.POST(this.messageEndpoint, this::handleMessage)
			.build();
	}

	/**
	 * 使用默认 SSE 端点构造一个新的 WebMvcSseServerTransport 实例。
	 * @param objectMapper 用于消息 JSON 序列化/反序列化的 ObjectMapper
	 * @param messageEndpoint 客户端应通过 HTTP POST 发送 JSON-RPC 消息的端点 URI。
	 * 此端点将通过 SSE 连接的初始端点事件通知给客户端。
	 * @throws IllegalArgumentException 如果 objectMapper 或 messageEndpoint 为 null
	 */
	public WebMvcSseServerTransport(ObjectMapper objectMapper, String messageEndpoint) {
		this(objectMapper, messageEndpoint, DEFAULT_SSE_ENDPOINT);
	}

	/**
	 * 设置此传输层的消息处理器。在 WebMVC SSE 实现中，此方法仅存储处理器以供后续使用，
	 * 因为连接是由客户端而不是服务器发起的。
	 * @param connectionHandler 处理传入的 JSON-RPC 消息并生成响应的函数
	 * @return 一个空的 Mono，因为服务器不发起连接
	 */
	@Override
	public Mono<Void> connect(
			Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> connectionHandler) {
		this.connectHandler = connectionHandler;
		// 服务器端传输层不发起连接
		return Mono.empty();
	}

	/**
	 * 通过 SSE 连接向所有连接的客户端广播消息。消息被序列化为 JSON 并作为类型为 "message" 的 SSE 事件发送。
	 * 如果在向特定客户端发送时发生错误，它们会被记录但不会阻止向其他客户端发送。
	 * @param message 要广播给所有连接客户端的 JSON-RPC 消息
	 * @return 一个在广播尝试完成时完成的 Mono
	 */
	@Override
	public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
		return Mono.fromRunnable(() -> {
			if (sessions.isEmpty()) {
				logger.debug("No active sessions to broadcast message to");
				return;
			}

			try {
				String jsonText = objectMapper.writeValueAsString(message);
				logger.debug("Attempting to broadcast message to {} active sessions", sessions.size());

				sessions.values().forEach(session -> {
					try {
						session.sseBuilder.id(session.id).event(MESSAGE_EVENT_TYPE).data(jsonText);
					}
					catch (Exception e) {
						logger.error("Failed to send message to session {}: {}", session.id, e.getMessage());
						session.sseBuilder.error(e);
					}
				});
			}
			catch (IOException e) {
				logger.error("Failed to serialize message: {}", e.getMessage());
			}
		});
	}

	/**
	 * 处理来自客户端的新 SSE 连接请求，通过创建新会话并建立 SSE 连接。此方法：
	 * <ul>
	 * <li>生成唯一的会话 ID</li>
	 * <li>创建带有 SSE builder 的新 ClientSession</li>
	 * <li>发送初始端点事件以通知客户端在哪里发送消息</li>
	 * <li>在会话映射中维护会话</li>
	 * </ul>
	 * @param request 传入的服务器请求
	 * @return 配置为 SSE 通信的 ServerResponse，如果服务器正在关闭或连接失败则返回错误响应
	 */
	private ServerResponse handleSseConnection(ServerRequest request) {
		if (this.isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		String sessionId = UUID.randomUUID().toString();
		logger.debug("Creating new SSE connection for session: {}", sessionId);

		// 发送初始端点事件
		try {
			return ServerResponse.sse(sseBuilder -> {
				sseBuilder.onComplete(() -> {
					logger.debug("SSE connection completed for session: {}", sessionId);
					sessions.remove(sessionId);
				});
				sseBuilder.onTimeout(() -> {
					logger.debug("SSE connection timed out for session: {}", sessionId);
					sessions.remove(sessionId);
				});

				ClientSession session = new ClientSession(sessionId, sseBuilder);
				this.sessions.put(sessionId, session);

				try {
					session.sseBuilder.id(session.id).event(ENDPOINT_EVENT_TYPE).data(messageEndpoint);
				}
				catch (Exception e) {
					logger.error("Failed to poll event from session queue: {}", e.getMessage());
					sseBuilder.error(e);
				}
			}, Duration.ZERO);
		}
		catch (Exception e) {
			logger.error("Failed to send initial endpoint event to session {}: {}", sessionId, e.getMessage());
			sessions.remove(sessionId);
			return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
	}

	/**
	 * 处理来自客户端的传入 JSON-RPC 消息。此方法：
	 * <ul>
	 * <li>将请求体反序列化为 JSON-RPC 消息</li>
	 * <li>通过配置的连接处理器处理消息</li>
	 * <li>根据处理结果返回适当的 HTTP 响应</li>
	 * </ul>
	 * @param request 包含 JSON-RPC 消息的传入服务器请求
	 * @return 表示成功（200 OK）的 ServerResponse，或在失败时返回带有错误详情的适当错误状态
	 */
	private ServerResponse handleMessage(ServerRequest request) {
		if (this.isClosing) {
			return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("Server is shutting down");
		}

		try {
			String body = request.body(String.class);
			McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body);

			// 将消息转换为 Mono，应用处理器，并阻塞等待响应
			@SuppressWarnings("unused")
			McpSchema.JSONRPCMessage response = Mono.just(message).transform(connectHandler).block();

			return ServerResponse.ok().build();
		}
		catch (IllegalArgumentException | IOException e) {
			logger.error("Failed to deserialize message: {}", e.getMessage());
			return ServerResponse.badRequest().body(new McpError("Invalid message format"));
		}
		catch (Exception e) {
			logger.error("Error handling message: {}", e.getMessage());
			return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new McpError(e.getMessage()));
		}
	}

	/**
	 * 表示具有其关联 SSE 连接的活动客户端会话。每个会话维护：
	 * <ul>
	 * <li>唯一的会话标识符</li>
	 * <li>用于向客户端发送服务器事件的 SSE builder</li>
	 * <li>会话生命周期事件的日志记录</li>
	 * </ul>
	 */
	private static class ClientSession {

		private final String id;

		private final SseBuilder sseBuilder;

		/**
		 * 使用指定的 ID 和 SSE builder 创建新的客户端会话。
		 * @param id 此会话的唯一标识符
		 * @param sseBuilder 用于向客户端发送服务器事件的 SSE builder
		 */
		ClientSession(String id, SseBuilder sseBuilder) {
			this.id = id;
			this.sseBuilder = sseBuilder;
			logger.debug("Session {} initialized with SSE emitter", id);
		}

		/**
		 * 通过完成 SSE 连接关闭此会话。完成过程中的任何错误都会被记录，
		 * 但不会阻止会话被标记为已关闭。
		 */
		void close() {
			logger.debug("Closing session: {}", id);
			try {
				sseBuilder.complete();
				logger.debug("Successfully completed SSE emitter for session {}", id);
			}
			catch (Exception e) {
				logger.warn("Failed to complete SSE emitter for session {}: {}", id, e.getMessage());
				// sseBuilder.error(e);
			}
		}

	}

	/**
	 * 使用配置的 ObjectMapper 将数据从一种类型转换为另一种类型。这对于处理复杂的 JSON-RPC 参数类型特别有用。
	 * @param data 要转换的源数据对象
	 * @param typeRef 目标类型引用
	 * @return 类型为 T 的转换后的对象
	 * @param <T> 目标类型
	 */
	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return this.objectMapper.convertValue(data, typeRef);
	}

	/**
	 * 启动传输层的优雅关闭。此方法：
	 * <ul>
	 * <li>设置关闭标志以防止新连接</li>
	 * <li>关闭所有活动的 SSE 连接</li>
	 * <li>移除所有会话记录</li>
	 * </ul>
	 * @return 一个在所有清理操作完成时完成的 Mono
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			this.isClosing = true;
			logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size());

			sessions.values().forEach(session -> {
				String sessionId = session.id;
				session.close();
				sessions.remove(sessionId);
			});

			logger.debug("Graceful shutdown completed");
		});
	}

	/**
	 * 返回定义此传输层 HTTP 端点的 RouterFunction。路由函数处理两个端点：
	 * <ul>
	 * <li>GET /sse - 用于建立 SSE 连接</li>
	 * <li>POST [messageEndpoint] - 用于接收来自客户端的 JSON-RPC 消息</li>
	 * </ul>
	 * @return 配置的用于处理 HTTP 请求的 RouterFunction
	 */
	public RouterFunction<ServerResponse> getRouterFunction() {
		return this.routerFunction;
	}

}
