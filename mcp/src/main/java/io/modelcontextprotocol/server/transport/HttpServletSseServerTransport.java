/*
* Copyright 2024 - 2024 the original author or authors.
*/
package io.modelcontextprotocol.server.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 基于Servlet的MCP HTTP与服务器发送事件(SSE)传输规范的实现。
 * 该实现提供了与WebFluxSseServerTransport类似的功能，但使用传统的Servlet API而不是WebFlux。
 *
 * @deprecated This class will be removed in 0.9.0. Use
 * {@link HttpServletSseServerTransportProvider}.
 *
 * <p>
 * 该传输处理两种类型的端点：
 * <ul>
 * <li>SSE端点 (/sse) - 建立用于服务器到客户端事件的长连接</li>
 * <li>消息端点 (可配置) - 处理客户端到服务器的消息请求</li>
 * </ul>
 *
 * <p>
 * 特性：
 * <ul>
 * <li>使用Servlet 6.0异步支持的异步消息处理</li>
 * <li>多客户端连接的会话管理</li>
 * <li>优雅关闭支持</li>
 * <li>错误处理和响应格式化</li>
 * </ul>
 * @author Christian Tzolov
 * @author Alexandros Pappas
 * @see ServerMcpTransport
 * @see HttpServlet
 */

@WebServlet(asyncSupported = true)
@Deprecated
public class HttpServletSseServerTransport extends HttpServlet implements ServerMcpTransport {

	/** 该类的日志记录器 */
	private static final Logger logger = LoggerFactory.getLogger(HttpServletSseServerTransport.class);

	public static final String UTF_8 = "UTF-8";

	public static final String APPLICATION_JSON = "application/json";

	public static final String FAILED_TO_SEND_ERROR_RESPONSE = "Failed to send error response: {}";

	/** Default endpoint path for SSE connections */
	public static final String DEFAULT_SSE_ENDPOINT = "/sse";

	/** Event type for regular messages */
	public static final String MESSAGE_EVENT_TYPE = "message";

	/** Event type for endpoint information */
	public static final String ENDPOINT_EVENT_TYPE = "endpoint";

	/** JSON object mapper for serialization/deserialization */
	private final ObjectMapper objectMapper;

	/** The endpoint path for handling client messages */
	private final String messageEndpoint;

	/** The endpoint path for handling SSE connections */
	private final String sseEndpoint;

	/** Map of active client sessions, keyed by session ID */
	private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();

	/** Flag indicating if the transport is in the process of shutting down */
	private final AtomicBoolean isClosing = new AtomicBoolean(false);

	/** Handler for processing incoming messages */
	private Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> connectHandler;

	/**
	 * 使用自定义SSE端点创建新的HttpServletSseServerTransport实例。
	 * @param objectMapper 用于消息序列化/反序列化的JSON对象映射器
	 * @param messageEndpoint 客户端发送消息的端点路径
	 * @param sseEndpoint 客户端建立SSE连接的端点路径
	 */
	public HttpServletSseServerTransport(ObjectMapper objectMapper, String messageEndpoint, String sseEndpoint) {
		this.objectMapper = objectMapper;
		this.messageEndpoint = messageEndpoint;
		this.sseEndpoint = sseEndpoint;
	}

	/**
	 * 使用默认SSE端点创建新的HttpServletSseServerTransport实例。
	 * @param objectMapper 用于消息序列化/反序列化的JSON对象映射器
	 * @param messageEndpoint 客户端发送消息的端点路径
	 */
	public HttpServletSseServerTransport(ObjectMapper objectMapper, String messageEndpoint) {
		this(objectMapper, messageEndpoint, DEFAULT_SSE_ENDPOINT);
	}

	/**
	 * 处理用于建立SSE连接的GET请求。
	 * <p>
	 * 当客户端连接到SSE端点时，此方法设置新的SSE连接。它配置SSE的响应头，
	 * 创建新会话，并向客户端发送初始端点信息。
	 * @param request HTTP servlet请求
	 * @param response HTTP servlet响应
	 * @throws ServletException 如果发生servlet特定错误
	 * @throws IOException 如果发生I/O错误
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		String pathInfo = request.getPathInfo();
		if (!sseEndpoint.equals(pathInfo)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		if (isClosing.get()) {
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
			return;
		}

		response.setContentType("text/event-stream");
		response.setCharacterEncoding(UTF_8);
		response.setHeader("Cache-Control", "no-cache");
		response.setHeader("Connection", "keep-alive");
		response.setHeader("Access-Control-Allow-Origin", "*");

		String sessionId = UUID.randomUUID().toString();
		AsyncContext asyncContext = request.startAsync();
		asyncContext.setTimeout(0);

		PrintWriter writer = response.getWriter();
		ClientSession session = new ClientSession(sessionId, asyncContext, writer);
		this.sessions.put(sessionId, session);

		// Send initial endpoint event
		this.sendEvent(writer, ENDPOINT_EVENT_TYPE, messageEndpoint);
	}

	/**
	 * 处理客户端消息的POST请求。
	 * <p>
	 * 此方法处理来自客户端的传入消息，如果配置了connect处理程序则通过它路由消息，
	 * 并发送回适当的响应。它处理错误情况并根据MCP规范格式化错误响应。
	 * @param request HTTP servlet请求
	 * @param response HTTP servlet响应
	 * @throws ServletException 如果发生servlet特定错误
	 * @throws IOException 如果发生I/O错误
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		if (isClosing.get()) {
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
			return;
		}

		String pathInfo = request.getPathInfo();
		if (!messageEndpoint.equals(pathInfo)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		try {
			BufferedReader reader = request.getReader();
			StringBuilder body = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				body.append(line);
			}

			McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body.toString());

			if (connectHandler != null) {
				connectHandler.apply(Mono.just(message)).subscribe(responseMessage -> {
					try {
						response.setContentType(APPLICATION_JSON);
						response.setCharacterEncoding(UTF_8);
						String jsonResponse = objectMapper.writeValueAsString(responseMessage);
						PrintWriter writer = response.getWriter();
						writer.write(jsonResponse);
						writer.flush();
					}
					catch (Exception e) {
						logger.error("Error sending response: {}", e.getMessage());
						try {
							response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
									"Error processing response: " + e.getMessage());
						}
						catch (IOException ex) {
							logger.error(FAILED_TO_SEND_ERROR_RESPONSE, ex.getMessage());
						}
					}
				}, error -> {
					try {
						logger.error("Error processing message: {}", error.getMessage());
						McpError mcpError = new McpError(error.getMessage());
						response.setContentType(APPLICATION_JSON);
						response.setCharacterEncoding(UTF_8);
						response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
						String jsonError = objectMapper.writeValueAsString(mcpError);
						PrintWriter writer = response.getWriter();
						writer.write(jsonError);
						writer.flush();
					}
					catch (IOException e) {
						logger.error(FAILED_TO_SEND_ERROR_RESPONSE, e.getMessage());
						try {
							response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
									"Error sending error response: " + e.getMessage());
						}
						catch (IOException ex) {
							logger.error(FAILED_TO_SEND_ERROR_RESPONSE, ex.getMessage());
						}
					}
				});
			}
			else {
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "No message handler configured");
			}
		}
		catch (Exception e) {
			logger.error("Invalid message format: {}", e.getMessage());
			try {
				McpError mcpError = new McpError("Invalid message format: " + e.getMessage());
				response.setContentType(APPLICATION_JSON);
				response.setCharacterEncoding(UTF_8);
				response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
				String jsonError = objectMapper.writeValueAsString(mcpError);
				PrintWriter writer = response.getWriter();
				writer.write(jsonError);
				writer.flush();
			}
			catch (IOException ex) {
				logger.error(FAILED_TO_SEND_ERROR_RESPONSE, ex.getMessage());
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid message format");
			}
		}
	}

	/**
	 * 设置用于处理客户端请求的消息处理程序。
	 * @param handler 处理传入消息并产生响应的函数
	 * @return 当处理程序设置完成时完成的Mono
	 */
	@Override
	public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		this.connectHandler = handler;
		return Mono.empty();
	}

	/**
	 * 向所有已连接的客户端广播消息。
	 * <p>
	 * 此方法序列化消息并将其发送到所有活动的客户端会话。
	 * 如果客户端断开连接，其会话将被移除。
	 * @param message 要广播的消息
	 * @return 当消息已发送给所有客户端时完成的Mono
	 */
	@Override
	public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
		if (sessions.isEmpty()) {
			logger.debug("No active sessions to broadcast message to");
			return Mono.empty();
		}

		return Mono.create(sink -> {
			try {
				String jsonText = objectMapper.writeValueAsString(message);

				sessions.values().forEach(session -> {
					try {
						this.sendEvent(session.writer, MESSAGE_EVENT_TYPE, jsonText);
					}
					catch (IOException e) {
						logger.error("Failed to send message to session {}: {}", session.id, e.getMessage());
						removeSession(session);
					}
				});

				sink.success();
			}
			catch (Exception e) {
				logger.error("Failed to process message: {}", e.getMessage());
				sink.error(new McpError("Failed to process message: " + e.getMessage()));
			}
		});
	}

	/**
	 * 关闭传输。
	 * <p>
	 * 此实现委托给父类的close方法。
	 */
	@Override
	public void close() {
		ServerMcpTransport.super.close();
	}

	/**
	 * 使用对象映射器将数据从一种类型转换为另一种类型。
	 * @param <T> 目标类型
	 * @param data 源数据
	 * @param typeRef 目标类型的类型引用
	 * @return 转换后的数据
	 */
	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return objectMapper.convertValue(data, typeRef);
	}

	/**
	 * 启动传输的优雅关闭。
	 * <p>
	 * 此方法将传输标记为正在关闭并关闭所有活动的客户端会话。
	 * 在关闭期间将拒绝新的连接尝试。
	 * @return 当所有会话都已关闭时完成的Mono
	 */
	@Override
	public Mono<Void> closeGracefully() {
		isClosing.set(true);
		logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size());

		return Mono.create(sink -> {
			sessions.values().forEach(this::removeSession);
			sink.success();
		});
	}

	/**
	 * 向客户端发送SSE事件。
	 * @param writer 用于发送事件的写入器
	 * @param eventType 事件类型（消息或端点）
	 * @param data 事件数据
	 * @throws IOException 如果在写入事件时发生错误
	 */
	private void sendEvent(PrintWriter writer, String eventType, String data) throws IOException {
		writer.write("event: " + eventType + "\n");
		writer.write("data: " + data + "\n\n");
		writer.flush();

		if (writer.checkError()) {
			throw new IOException("Client disconnected");
		}
	}

	/**
	 * 移除客户端会话并完成其异步上下文。
	 * @param session 要移除的会话
	 */
	private void removeSession(ClientSession session) {
		sessions.remove(session.id);
		session.asyncContext.complete();
	}

	/**
	 * 表示客户端连接会话。
	 * <p>
	 * 此类保存有关客户端SSE连接的必要信息，
	 * 包括其ID、异步上下文和输出写入器。
	 */
	private static class ClientSession {

		private final String id;

		private final AsyncContext asyncContext;

		private final PrintWriter writer;

		ClientSession(String id, AsyncContext asyncContext, PrintWriter writer) {
			this.id = id;
			this.asyncContext = asyncContext;
			this.writer = writer;
		}

	}

	/**
	 * 在servlet被销毁时清理资源。
	 * <p>
	 * 此方法通过在调用父类的destroy方法之前关闭所有客户端连接来确保优雅关闭。
	 */
	@Override
	public void destroy() {
		closeGracefully().block();
		super.destroy();
	}

}
