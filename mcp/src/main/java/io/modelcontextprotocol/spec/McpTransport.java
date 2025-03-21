/*
 * 版权所有 2024-2024 原始作者或作者们。
 */

 package io.modelcontextprotocol.spec;

 import java.util.function.Function;
 
 import com.fasterxml.jackson.core.type.TypeReference;
 import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
 import reactor.core.publisher.Mono;
 
 /**
  * 定义了模型上下文协议（MCP）的异步传输层。
  *
  * <p>
  * McpTransport 接口为在模型上下文协议中实现自定义传输机制提供了基础。它处理客户端和服务器组件之间的双向通信，支持使用 JSON-RPC 格式进行异步消息交换。
  * </p>
  *
  * <p>
  * 此接口的实现负责：
  * </p>
  * <ul>
  * <li>管理传输连接的生命周期</li>
  * <li>处理来自服务器的传入消息和错误</li>
  * <li>向服务器发送传出消息</li>
  * </ul>
  *
  * <p>
  * 传输层设计为与协议无关，允许实现诸如 WebSocket、HTTP 或自定义协议等多种方式。
  * </p>
  *
  * @author Christian Tzolov
  * @author Dariusz Jędrzejczyk
  */
 public interface McpTransport {
 
	 /**
	  * 初始化并启动传输连接。
	  *
	  * <p>
	  * 在进行任何消息交换之前，应调用此方法。它会设置必要的资源并建立与服务器的连接。
	  * </p>
	  */
	 Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler);
 
	 /**
	  * 关闭传输连接并释放任何相关资源。
	  *
	  * <p>
	  * 当传输不再需要时，此方法确保正确清理资源。它应处理任何活动连接的优雅关闭。
	  * </p>
	  */
	 default void close() {
		 this.closeGracefully().subscribe();
	 }
 
	 /**
	  * 异步关闭传输连接并释放任何相关资源。
	  * @return 一个在连接关闭时完成的 {@link Mono<Void>}。
	  */
	 Mono<Void> closeGracefully();
 
	 /**
	  * 异步向服务器发送消息。
	  *
	  * <p>
	  * 此方法以异步方式处理向服务器传输消息。消息按照 MCP 协议指定的 JSON-RPC 格式发送。
	  * </p>
	  * @param message 要发送到服务器的 {@link JSONRPCMessage}
	  * @return 一个在消息发送完成后完成的 {@link Mono<Void>}
	  */
	 Mono<Void> sendMessage(JSONRPCMessage message);
 
	 /**
	  * 将给定数据解码为指定类型的对象。
	  * @param <T> 要解码的对象类型
	  * @param data 要解码的数据
	  * @param typeRef 要解码的对象的类型引用
	  * @return 解码后的对象
	  */
	 <T> T unmarshalFrom(Object data, TypeReference<T> typeRef);
 
 }