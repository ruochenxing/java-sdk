/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.util.Assert;

/**
 * MCP（Model Context Protocol）服务器的同步实现类，它包装了 {@link McpAsyncServer} 以提供阻塞操作。
 * 该类将所有操作委托给底层的异步服务器实例，同时为不需要响应式编程的场景提供更简单的同步 API。
 *
 * <p>
 * MCP 服务器使 AI 模型能够通过标准化接口暴露工具、资源和提示词。通过这个同步 API 可用的主要特性包括：
 * <ul>
 * <li>用于扩展 AI 模型能力的工具注册和管理</li>
 * <li>基于 URI 的资源处理，用于提供上下文</li>
 * <li>用于标准化交互的提示词模板管理</li>
 * <li>状态变更的实时客户端通知</li>
 * <li>可配置严重级别的结构化日志</li>
 * <li>支持客户端 AI 模型采样</li>
 * </ul>
 *
 * <p>
 * 虽然 {@link McpAsyncServer} 使用 Project Reactor 的 Mono 和 Flux 类型进行非阻塞操作，
 * 但该类将这些转换为阻塞调用，使其更适合：
 * <ul>
 * <li>传统的同步应用程序</li>
 * <li>简单的脚本场景</li>
 * <li>测试和调试</li>
 * <li>响应式编程增加不必要复杂性的情况</li>
 * </ul>
 *
 * <p>
 * 服务器支持通过 {@link #addTool}、{@link #addResource} 和 {@link #addPrompt} 等方法
 * 在运行时修改其功能，并在配置为这样做时自动通知连接的客户端。
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @see McpAsyncServer
 * @see McpSchema
 */
public class McpSyncServer {

	/**
	 * 底层的异步服务器实例
	 */
	private final McpAsyncServer asyncServer;

	/**
	 * 构造函数，使用异步服务器实例创建同步服务器
	 * @param asyncServer 异步服务器实例
	 */
	public McpSyncServer(McpAsyncServer asyncServer) {
		Assert.notNull(asyncServer, "Async server must not be null");
		this.asyncServer = asyncServer;
	}

	/**
	 * 列出所有根资源
	 * @return 包含根资源列表的结果
	 */
	public McpSchema.ListRootsResult listRoots() {
		return this.listRoots(null);
	}

	/**
	 * 使用游标列出根资源
	 * @param cursor 分页游标
	 * @return 包含根资源列表的结果
	 */
	public McpSchema.ListRootsResult listRoots(String cursor) {
		return this.asyncServer.listRoots(cursor).block();
	}

	/**
	 * 添加新工具
	 * @param toolHandler 工具处理器
	 */
	public void addTool(McpServerFeatures.SyncToolRegistration toolHandler) {
		this.asyncServer.addTool(McpServerFeatures.AsyncToolRegistration.fromSync(toolHandler)).block();
	}

	/**
	 * 移除指定工具
	 * @param toolName 工具名称
	 */
	public void removeTool(String toolName) {
		this.asyncServer.removeTool(toolName).block();
	}

	/**
	 * 添加新资源
	 * @param resourceHandler 资源处理器
	 */
	public void addResource(McpServerFeatures.SyncResourceRegistration resourceHandler) {
		this.asyncServer.addResource(McpServerFeatures.AsyncResourceRegistration.fromSync(resourceHandler)).block();
	}

	/**
	 * 移除指定资源
	 * @param resourceUri 资源URI
	 */
	public void removeResource(String resourceUri) {
		this.asyncServer.removeResource(resourceUri).block();
	}

	/**
	 * 添加新提示词
	 * @param promptRegistration 提示词注册信息
	 */
	public void addPrompt(McpServerFeatures.SyncPromptRegistration promptRegistration) {
		this.asyncServer.addPrompt(McpServerFeatures.AsyncPromptRegistration.fromSync(promptRegistration)).block();
	}

	/**
	 * 移除指定提示词
	 * @param promptName 提示词名称
	 */
	public void removePrompt(String promptName) {
		this.asyncServer.removePrompt(promptName).block();
	}

	/**
	 * 通知客户端工具列表已更改
	 */
	public void notifyToolsListChanged() {
		this.asyncServer.notifyToolsListChanged().block();
	}

	/**
	 * 获取服务器能力配置
	 * @return 服务器能力配置对象
	 */
	public McpSchema.ServerCapabilities getServerCapabilities() {
		return this.asyncServer.getServerCapabilities();
	}

	/**
	 * 获取服务器实现信息
	 * @return 服务器实现信息对象
	 */
	public McpSchema.Implementation getServerInfo() {
		return this.asyncServer.getServerInfo();
	}

	/**
	 * 获取客户端能力配置
	 * @return 客户端能力配置对象
	 */
	public ClientCapabilities getClientCapabilities() {
		return this.asyncServer.getClientCapabilities();
	}

	/**
	 * 获取客户端实现信息
	 * @return 客户端实现信息对象
	 */
	public McpSchema.Implementation getClientInfo() {
		return this.asyncServer.getClientInfo();
	}

	/**
	 * 通知客户端资源列表已更改
	 */
	public void notifyResourcesListChanged() {
		this.asyncServer.notifyResourcesListChanged().block();
	}

	/**
	 * 通知客户端提示词列表已更改
	 */
	public void notifyPromptsListChanged() {
		this.asyncServer.notifyPromptsListChanged().block();
	}

	/**
	 * 发送日志通知
	 * @param loggingMessageNotification 日志消息通知
	 */
	public void loggingNotification(LoggingMessageNotification loggingMessageNotification) {
		this.asyncServer.loggingNotification(loggingMessageNotification).block();
	}

	/**
	 * 优雅关闭服务器
	 */
	public void closeGracefully() {
		this.asyncServer.closeGracefully().block();
	}

	/**
	 * 强制关闭服务器
	 */
	public void close() {
		this.asyncServer.close();
	}

	/**
	 * 获取底层的异步服务器实例
	 * @return 异步服务器实例
	 */
	public McpAsyncServer getAsyncServer() {
		return this.asyncServer;
	}

	/**
	 * 创建新消息
	 * @param createMessageRequest 创建消息请求
	 * @return 包含创建结果的消息
	 */
	public McpSchema.CreateMessageResult createMessage(McpSchema.CreateMessageRequest createMessageRequest) {
		return this.asyncServer.createMessage(createMessageRequest).block();
	}

}
