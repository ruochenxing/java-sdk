/*
 * 版权所有 2024-2024 原始作者或作者们。
 */

 package io.modelcontextprotocol.spec;

 import java.time.Duration;
 import java.util.Map;
 import java.util.UUID;
 import java.util.concurrent.ConcurrentHashMap;
 import java.util.concurrent.atomic.AtomicLong;
 
 import com.fasterxml.jackson.core.type.TypeReference;
 import io.modelcontextprotocol.util.Assert;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
 import reactor.core.Disposable;
 import reactor.core.publisher.Mono;
 import reactor.core.publisher.MonoSink;
 
 /**
  * MCP（模型上下文协议）会话的默认实现，用于管理客户端和服务器之间的双向 JSON-RPC 通信。此实现遵循 MCP 规范的消息交换和传输处理。
  *
  * <p>
  * 该会话管理以下内容：
  * <ul>
  * <li>具有唯一消息 ID 的请求/响应处理</li>
  * <li>通知处理</li>
  * <li>消息超时管理</li>
  * <li>传输层抽象</li>
  * </ul>
  *
  * @author Christian Tzolov
  * @author Dariusz Jędrzejczyk
  */
 public class DefaultMcpSession implements McpSession {
 
	 /** 此类的日志记录器 */
	 private static final Logger logger = LoggerFactory.getLogger(DefaultMcpSession.class);
 
	 /** 在超时前等待请求响应的持续时间 */
	 private final Duration requestTimeout;
 
	 /** 用于消息交换的传输层实现 */
	 private final McpTransport transport;
 
	 /** 按请求 ID 键控的待处理响应的映射 */
	 private final ConcurrentHashMap<Object, MonoSink<McpSchema.JSONRPCResponse>> pendingResponses = new ConcurrentHashMap<>();
 
	 /** 按方法名称键控的请求处理程序映射 */
	 private final ConcurrentHashMap<String, RequestHandler<?>> requestHandlers = new ConcurrentHashMap<>();
 
	 /** 按方法名称键控的通知处理程序映射 */
	 private final ConcurrentHashMap<String, NotificationHandler> notificationHandlers = new ConcurrentHashMap<>();
 
	 /** 会话特定的请求 ID 前缀 */
	 private final String sessionPrefix = UUID.randomUUID().toString().substring(0, 8);
 
	 /** 用于生成唯一请求 ID 的原子计数器 */
	 private final AtomicLong requestCounter = new AtomicLong(0);
 
	 private final Disposable connection;
 
	 /**
	  * 用于处理传入 JSON-RPC 请求的功能接口。实现应处理请求参数并返回响应。
	  *
	  * @param <T> 响应类型
	  */
	 @FunctionalInterface
	 public interface RequestHandler<T> {
 
		 /**
		  * 处理具有给定参数的传入请求。
		  * @param params 请求参数
		  * @return 包含响应对象的 Mono
		  */
		 Mono<T> handle(Object params);
 
	 }
 
	 /**
	  * 用于处理传入 JSON-RPC 通知的功能接口。实现应处理通知参数而不返回响应。
	  */
	 @FunctionalInterface
	 public interface NotificationHandler {
 
		 /**
		  * 处理具有给定参数的传入通知。
		  * @param params 通知参数
		  * @return 在通知处理完成时完成的 Mono
		  */
		 Mono<Void> handle(Object params);
 
	 }
 
	 /**
	  * 使用指定的配置和处理程序创建新的 DefaultMcpSession。
	  * @param requestTimeout 等待响应的持续时间
	  * @param transport 用于消息交换的传输实现
	  * @param requestHandlers 方法名称到请求处理程序的映射
	  * @param notificationHandlers 方法名称到通知处理程序的映射
	  */
	 public DefaultMcpSession(Duration requestTimeout, McpTransport transport,
			 Map<String, RequestHandler<?>> requestHandlers, Map<String, NotificationHandler> notificationHandlers) {
 
		 Assert.notNull(requestTimeout, "请求超时不能为空");
		 Assert.notNull(transport, "传输不能为空");
		 Assert.notNull(requestHandlers, "请求处理程序不能为空");
		 Assert.notNull(notificationHandlers, "通知处理程序不能为空");
 
		 this.requestTimeout = requestTimeout;
		 this.transport = transport;
		 this.requestHandlers.putAll(requestHandlers);
		 this.notificationHandlers.putAll(notificationHandlers);
 
		 // TODO: 考虑使用 mono.transformDeferredContextual，其中 Context 包含与单个消息关联的 Observation，
		 // 可用于创建子 Observation 并将其与消息一起发送给消费者
		 this.connection = this.transport.connect(mono -> mono.doOnNext(message -> {
			 if (message instanceof McpSchema.JSONRPCResponse response) {
				 logger.debug("收到响应: {}", response);
				 var sink = pendingResponses.remove(response.id());
				 if (sink == null) {
					 logger.warn("收到未知 ID 的意外响应 {}", response.id());
				 }
				 else {
					 sink.success(response);
				 }
			 }
			 else if (message instanceof McpSchema.JSONRPCRequest request) {
				 logger.debug("收到请求: {}", request);
				 handleIncomingRequest(request).subscribe(response -> transport.sendMessage(response).subscribe(),
						 error -> {
							 var errorResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(),
									 null, new McpSchema.JSONRPCResponse.JSONRPCError(
											 McpSchema.ErrorCodes.INTERNAL_ERROR, error.getMessage(), null));
							 transport.sendMessage(errorResponse).subscribe();
						 });
			 }
			 else if (message instanceof McpSchema.JSONRPCNotification notification) {
				 logger.debug("收到通知: {}", notification);
				 handleIncomingNotification(notification).subscribe(null,
						 error -> logger.error("处理通知时出错: {}", error.getMessage()));
			 }
		 })).subscribe();
	 }
 
	 /**
	  * 通过将传入的 JSON-RPC 请求路由到适当的处理程序来处理它。
	  * @param request 传入的 JSON-RPC 请求
	  * @return 包含 JSON-RPC 响应的 Mono
	  */
	 private Mono<McpSchema.JSONRPCResponse> handleIncomingRequest(McpSchema.JSONRPCRequest request) {
		 return Mono.defer(() -> {
			 var handler = this.requestHandlers.get(request.method());
			 if (handler == null) {
				 MethodNotFoundError error = getMethodNotFoundError(request.method());
				 return Mono.just(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
						 new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.METHOD_NOT_FOUND,
								 error.message(), error.data())));
			 }
 
			 return handler.handle(request.params())
				 .map(result -> new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null))
				 .onErrorResume(error -> Mono.just(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(),
						 null, new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
								 error.getMessage(), null)))); // TODO: 通过 data 字段添加错误消息
		 });
	 }
 
	 record MethodNotFoundError(String method, String message, Object data) {
	 }
 
	 public static MethodNotFoundError getMethodNotFoundError(String method) {
		 switch (method) {
			 case McpSchema.METHOD_ROOTS_LIST:
				 return new MethodNotFoundError(method, "不支持根列表",
						 Map.of("reason", "客户端没有根功能"));
			 default:
				 return new MethodNotFoundError(method, "未找到方法: " + method, null);
		 }
	 }
 
	 /**
	  * 通过将传入的 JSON-RPC 通知路由到适当的处理程序来处理它。
	  * @param notification 传入的 JSON-RPC 通知
	  * @return 在通知处理完成时完成的 Mono
	  */
	 private Mono<Void> handleIncomingNotification(McpSchema.JSONRPCNotification notification) {
		 return Mono.defer(() -> {
			 var handler = notificationHandlers.get(notification.method());
			 if (handler == null) {
				 logger.error("未为通知方法注册处理程序: {}", notification.method());
				 return Mono.empty();
			 }
			 return handler.handle(notification.params());
		 });
	 }
 
	 /**
	  * 以非阻塞方式生成唯一的请求 ID。结合会话特定的前缀和原子计数器以确保唯一性。
	  * @return 唯一的请求 ID 字符串
	  */
	 private String generateRequestId() {
		 return this.sessionPrefix + "-" + this.requestCounter.getAndIncrement();
	 }
 
	 /**
	  * 发送 JSON-RPC 请求并返回响应。
	  * @param <T> 期望的响应类型
	  * @param method 要调用的方法名称
	  * @param requestParams 请求参数
	  * @param typeRef 响应反序列化的类型引用
	  * @return 包含响应的 Mono
	  */
	 @Override
	 public <T> Mono<T> sendRequest(String method, Object requestParams, TypeReference<T> typeRef) {
		 String requestId = this.generateRequestId();
 
		 return Mono.<McpSchema.JSONRPCResponse>create(sink -> {
			 this.pendingResponses.put(requestId, sink);
			 McpSchema.JSONRPCRequest jsonrpcRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method,
					 requestId, requestParams);
			 this.transport.sendMessage(jsonrpcRequest)
				 // TODO: 在这里创建一个专用的 Subscriber 是最高效的
				 .subscribe(v -> {
				 }, error -> {
					 this.pendingResponses.remove(requestId);
					 sink.error(error);
				 });
		 }).timeout(this.requestTimeout).handle((jsonRpcResponse, sink) -> {
			 if (jsonRpcResponse.error() != null) {
				 sink.error(new McpError(jsonRpcResponse.error()));
			 }
			 else {
				 if (typeRef.getType().equals(Void.class)) {
					 sink.complete();
				 }
				 else {
					 sink.next(this.transport.unmarshalFrom(jsonRpcResponse.result(), typeRef));
				 }
			 }
		 });
	 }
 
	 /**
	  * 发送 JSON-RPC 通知。
	  * @param method 通知的方法名称
	  * @param params 通知参数
	  * @return 在通知发送完成时完成的 Mono
	  */
	 @Override
	 public Mono<Void> sendNotification(String method, Map<String, Object> params) {
		 McpSchema.JSONRPCNotification jsonrpcNotification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				 method, params);
		 return this.transport.sendMessage(jsonrpcNotification);
	 }
 
	 /**
	  * 优雅地关闭会话，允许待处理操作完成。
	  * @return 在会话关闭时完成的 Mono
	  */
	 @Override
	 public Mono<Void> closeGracefully() {
		 this.connection.dispose();
		 return transport.closeGracefully();
	 }
 
	 /**
	  * 立即关闭会话，可能会中断待处理操作。
	  */
	 @Override
	 public void close() {
		 this.connection.dispose();
		 transport.close();
	 }
 
 }