/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransport;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * MCP（Model Context Protocol）服务器的工厂类。MCP服务器通过标准化接口向AI模型暴露工具、资源和提示词。
 *
 * <p>
 * 该类作为实现MCP规范服务器端的主要入口点。服务器的主要职责包括：
 * <ul>
 * <li>暴露可被模型调用的工具以执行操作</li>
 * <li>提供对资源的访问，为模型提供上下文</li>
 * <li>管理用于结构化模型交互的提示词模板</li>
 * <li>处理客户端连接和请求</li>
 * <li>实现功能协商</li>
 * </ul>
 *
 * <p>
 * 线程安全说明：
 * - 同步服务器：按顺序处理请求，适合简单场景
 * - 异步服务器：通过响应式编程模型处理并发请求，适合高并发场景
 *
 * <p>
 * 错误处理：
 * - 使用McpError类提供统一的错误处理机制
 * - 错误会被适当地传播给客户端
 * - 服务器实现应提供有意义的错误消息
 *
 * <p>
 * 基础同步服务器创建示例：
 * <pre>{@code
 * McpServer.sync(transport)
 *     .serverInfo("my-server", "1.0.0")
 *     .tool(new Tool("calculator", "执行计算", schema),
 *           args -> new CallToolResult("结果: " + calculate(args)))
 *     .build();
 * }</pre>
 *
 * 基础异步服务器创建示例：
 * <pre>{@code
 * McpServer.async(transport)
 *     .serverInfo("my-server", "1.0.0")
 *     .tool(new Tool("calculator", "执行计算", schema),
 *           args -> Mono.just(new CallToolResult("结果: " + calculate(args))))
 *     .build();
 * }</pre>
 *
 * <p>
 * 完整的异步配置示例：
 * <pre>{@code
 * McpServer.async(transport)
 *     .serverInfo("advanced-server", "2.0.0")
 *     .capabilities(new ServerCapabilities(...))
 *     // 注册工具
 *     .tools(
 *         new McpServerFeatures.AsyncToolRegistration(calculatorTool,
 *             args -> Mono.just(new CallToolResult("结果: " + calculate(args)))),
 *         new McpServerFeatures.AsyncToolRegistration(weatherTool,
 *             args -> Mono.just(new CallToolResult("天气: " + getWeather(args))))
 *     )
 *     // 注册资源
 *     .resources(
 *         new McpServerFeatures.AsyncResourceRegistration(fileResource,
 *             req -> Mono.just(new ReadResourceResult(readFile(req)))),
 *         new McpServerFeatures.AsyncResourceRegistration(dbResource,
 *             req -> Mono.just(new ReadResourceResult(queryDb(req))))
 *     )
 *     // 添加资源模板
 *     .resourceTemplates(
 *         new ResourceTemplate("file://{path}", "访问文件"),
 *         new ResourceTemplate("db://{table}", "访问数据库")
 *     )
 *     // 注册提示词
 *     .prompts(
 *         new McpServerFeatures.AsyncPromptRegistration(analysisPrompt,
 *             req -> Mono.just(new GetPromptResult(generateAnalysisPrompt(req)))),
 *         new McpServerFeatures.AsyncPromptRegistration(summaryPrompt,
 *             req -> Mono.just(new GetPromptResult(generateSummaryPrompt(req))))
 *     )
 *     .build();
 * }</pre>
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @see McpAsyncServer
 * @see McpSyncServer
 * @see McpTransport
 */
public interface McpServer {

	/**
	 * 创建同步MCP服务器的工厂方法。
	 * 同步服务器在处理下一个请求之前会完成当前请求的处理，
	 * 这使得它们实现更简单，但在并发操作时性能可能较低。
	 * @param transport 用于MCP通信的传输层实现
	 * @return 一个新的 {@link SyncSpec} 实例，用于配置服务器
	 * @throws IllegalArgumentException 如果transport为null
	 */
	static SyncSpec sync(ServerMcpTransport transport) {
		return new SyncSpec(transport);
	}

	/**
	 * 创建异步MCP服务器的工厂方法。
	 * 异步服务器可以使用函数式范式和非阻塞服务器传输同时处理多个请求，
	 * 这使得它们在高并发场景下更高效，但实现更复杂。
	 * @param transport 用于MCP通信的传输层实现
	 * @return 一个新的 {@link AsyncSpec} 实例，用于配置服务器
	 * @throws IllegalArgumentException 如果transport为null
	 */
	static AsyncSpec async(ServerMcpTransport transport) {
		return new AsyncSpec(transport);
	}

	/**
	 * 异步服务器规范类。
	 * 用于配置和构建异步MCP服务器的所有必要参数。
	 */
	class AsyncSpec {

		/**
		 * 默认的服务器信息配置
		 */
		private static final McpSchema.Implementation DEFAULT_SERVER_INFO = new McpSchema.Implementation("mcp-server",
				"1.0.0");

		/**
		 * MCP通信的传输层实现
		 */
		private final ServerMcpTransport transport;

		/**
		 * 服务器实现信息，包含名称和版本
		 */
		private McpSchema.Implementation serverInfo = DEFAULT_SERVER_INFO;

		/**
		 * 服务器能力配置，定义服务器支持的功能特性
		 */
		private McpSchema.ServerCapabilities serverCapabilities;

		/**
		 * MCP服务器暴露的工具列表。
		 * 工具允许AI模型与外部系统交互，例如查询数据库、调用API或执行计算。
		 * 每个工具都有唯一的名称和描述其功能的元数据。
		 */
		private final List<McpServerFeatures.AsyncToolRegistration> tools = new ArrayList<>();

		/**
		 * MCP服务器暴露的资源映射。
		 * 资源允许服务器共享数据以为AI模型提供上下文，如文件、数据库模式或应用特定信息。
		 * 每个资源都由URI唯一标识。
		 */
		private final Map<String, McpServerFeatures.AsyncResourceRegistration> resources = new HashMap<>();

		/**
		 * 资源模板列表，定义了动态资源访问的模式
		 */
		private final List<ResourceTemplate> resourceTemplates = new ArrayList<>();

		/**
		 * MCP服务器暴露的提示词模板映射。
		 * 提示词允许服务器提供结构化的消息和指令，用于与AI模型交互。
		 * 客户端可以发现可用的提示词，获取其内容，并提供参数来自定义它们。
		 */
		private final Map<String, McpServerFeatures.AsyncPromptRegistration> prompts = new HashMap<>();

		/**
		 * 根资源变更的监听器列表
		 */
		private final List<Function<List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers = new ArrayList<>();

		/**
		 * 构造函数
		 * @param transport MCP通信的传输层实现
		 * @throws IllegalArgumentException 如果transport为null
		 */
		private AsyncSpec(ServerMcpTransport transport) {
			Assert.notNull(transport, "Transport must not be null");
			this.transport = transport;
		}

		/**
		 * 设置服务器实现信息。
		 * 这些信息将在连接初始化期间与客户端共享，有助于版本兼容性、调试和服务器识别。
		 * 
		 * @param serverInfo 服务器实现详情，包括名称和版本
		 * @return 当前构建器实例，用于方法链式调用
		 * @throws IllegalArgumentException 如果serverInfo为null
		 */
		public AsyncSpec serverInfo(McpSchema.Implementation serverInfo) {
			Assert.notNull(serverInfo, "服务器信息不能为null");
			this.serverInfo = serverInfo;
			return this;
		}

		/**
		 * 使用名称和版本字符串设置服务器实现信息。
		 * 这是{@link #serverInfo(McpSchema.Implementation)}方法的便捷替代方案。
		 * 
		 * @param name 服务器名称，不能为null或空
		 * @param version 服务器版本，不能为null或空
		 * @return 当前构建器实例，用于方法链式调用
		 * @throws IllegalArgumentException 如果name或version为null或空
		 */
		public AsyncSpec serverInfo(String name, String version) {
			Assert.hasText(name, "名称不能为null或空");
			Assert.hasText(version, "版本不能为null或空");
			this.serverInfo = new McpSchema.Implementation(name, version);
			return this;
		}

		/**
		 * 设置服务器能力配置。
		 * 这些能力将在连接初始化期间向客户端公布。能力定义了服务器支持的功能，例如：
		 * <ul>
		 * <li>工具执行</li>
		 * <li>资源访问</li>
		 * <li>提示词处理</li>
		 * <li>流式响应</li>
		 * <li>批量操作</li>
		 * </ul>
		 * 
		 * @param serverCapabilities 服务器能力配置
		 * @return 当前构建器实例，用于方法链式调用
		 */
		public AsyncSpec capabilities(McpSchema.ServerCapabilities serverCapabilities) {
			this.serverCapabilities = serverCapabilities;
			return this;
		}

		/**
		 * 添加单个工具及其实现处理器到服务器。
		 * 这是一个便捷方法，无需显式创建AsyncToolRegistration。
		 *
		 * 使用示例：
		 * <pre>{@code
		 * .tool(
		 *     new Tool("calculator", "执行计算操作", schema),
		 *     args -> Mono.just(new CallToolResult("结果: " + calculate(args)))
		 * )
		 * }</pre>
		 * 
		 * @param tool 工具定义，包括名称、描述和模式
		 * @param handler 实现工具逻辑的函数
		 * @return 当前构建器实例，用于方法链式调用
		 * @throws IllegalArgumentException 如果tool或handler为null
		 */
		public AsyncSpec tool(McpSchema.Tool tool, 
							Function<Map<String, Object>, Mono<CallToolResult>> handler) {
			Assert.notNull(tool, "工具不能为null");
			Assert.notNull(handler, "处理器不能为null");
			this.tools.add(new McpServerFeatures.AsyncToolRegistration(tool, handler));
			return this;
		}

		/**
		 * 使用List添加多个工具及其处理器到服务器。
		 * 当工具是动态生成或从配置源加载时，此方法很有用。
		 *
		 * @param toolRegistrations 要添加的工具注册列表，不能为null
		 * @return 当前构建器实例，用于方法链式调用
		 * @throws IllegalArgumentException 如果toolRegistrations为null
		 * @see #tools(McpServerFeatures.AsyncToolRegistration...)
		 */
		public AsyncSpec tools(List<McpServerFeatures.AsyncToolRegistration> toolRegistrations) {
			Assert.notNull(toolRegistrations, "工具处理器列表不能为null");
			this.tools.addAll(toolRegistrations);
			return this;
		}

		/**
		 * 使用可变参数添加多个工具及其处理器到服务器。
		 * 此方法提供了一种便捷的方式来内联注册多个工具。
		 *
		 * 使用示例：
		 * <pre>{@code
		 * .tools(
		 *     new McpServerFeatures.AsyncToolRegistration(calculatorTool, calculatorHandler),
		 *     new McpServerFeatures.AsyncToolRegistration(weatherTool, weatherHandler),
		 *     new McpServerFeatures.AsyncToolRegistration(fileManagerTool, fileManagerHandler)
		 * )
		 * }</pre>
		 * 
		 * @param toolRegistrations 要添加的工具注册列表，不能为null
		 * @return 当前构建器实例，用于方法链式调用
		 * @throws IllegalArgumentException 如果toolRegistrations为null
		 * @see #tools(List)
		 */
		public AsyncSpec tools(McpServerFeatures.AsyncToolRegistration... toolRegistrations) {
			for (McpServerFeatures.AsyncToolRegistration tool : toolRegistrations) {
				this.tools.add(tool);
			}
			return this;
		}

		/**
		 * 使用Map注册多个资源及其处理器。
		 * 当资源是动态生成或从配置源加载时，此方法很有用。
		 *
		 * @param resourceRegsitrations 资源名称到注册的映射，不能为null
		 * @return 当前构建器实例，用于方法链式调用
		 * @throws IllegalArgumentException 如果resourceRegsitrations为null
		 * @see #resources(McpServerFeatures.AsyncResourceRegistration...)
		 */
		public AsyncSpec resources(Map<String, McpServerFeatures.AsyncResourceRegistration> resourceRegsitrations) {
			Assert.notNull(resourceRegsitrations, "资源处理器映射不能为null");
			this.resources.putAll(resourceRegsitrations);
			return this;
		}

		/**
		 * 使用List注册多个资源及其处理器。
		 * 当需要从集合中批量添加资源时，此方法很有用。
		 *
		 * @param resourceRegsitrations 资源注册列表，不能为null
		 * @return 当前构建器实例，用于方法链式调用
		 * @throws IllegalArgumentException 如果resourceRegsitrations为null
		 * @see #resources(McpServerFeatures.AsyncResourceRegistration...)
		 */
		public AsyncSpec resources(List<McpServerFeatures.AsyncResourceRegistration> resourceRegsitrations) {
			Assert.notNull(resourceRegsitrations, "资源处理器列表不能为null");
			for (McpServerFeatures.AsyncResourceRegistration resource : resourceRegsitrations) {
				this.resources.put(resource.resource().uri(), resource);
			}
			return this;
		}

		/**
		 * 使用可变参数注册多个资源及其处理器。
		 * 此方法提供了一种便捷的方式来内联注册多个资源。
		 *
		 * 使用示例：
		 * <pre>{@code
		 * .resources(
		 *     new McpServerFeatures.AsyncResourceRegistration(fileResource, fileHandler),
		 *     new McpServerFeatures.AsyncResourceRegistration(dbResource, dbHandler),
		 *     new McpServerFeatures.AsyncResourceRegistration(apiResource, apiHandler)
		 * )
		 * }</pre>
		 * 
		 * @param resourceRegistrations 要添加的资源注册列表，不能为null
		 * @return 当前构建器实例，用于方法链式调用
		 * @throws IllegalArgumentException 如果resourceRegistrations为null
		 */
		public AsyncSpec resources(McpServerFeatures.AsyncResourceRegistration... resourceRegistrations) {
			Assert.notNull(resourceRegistrations, "资源处理器列表不能为null");
			for (McpServerFeatures.AsyncResourceRegistration resource : resourceRegistrations) {
				this.resources.put(resource.resource().uri(), resource);
			}
			return this;
		}

		/**
		 * 设置用于定义动态资源访问模式的资源模板。
		 * 模板使用带有占位符的URI模式，这些占位符可以在运行时填充。
		 *
		 * 使用示例：
		 * <pre>{@code
		 * .resourceTemplates(
		 *     new ResourceTemplate("file://{path}", "通过路径访问文件"),
		 *     new ResourceTemplate("db://{table}/{id}", "访问数据库记录")
		 * )
		 * }</pre>
		 * 
		 * @param resourceTemplates 资源模板列表，如果为null则清除现有模板
		 * @return 当前构建器实例，用于方法链式调用
		 * @see #resourceTemplates(ResourceTemplate...)
		 */
		public AsyncSpec resourceTemplates(List<ResourceTemplate> resourceTemplates) {
			this.resourceTemplates.addAll(resourceTemplates);
			return this;
		}

		/**
		 * 使用可变参数设置资源模板。
		 * 这是{@link #resourceTemplates(List)}方法的便捷替代方案。
		 *
		 * @param resourceTemplates 要设置的资源模板
		 * @return 当前构建器实例，用于方法链式调用
		 * @see #resourceTemplates(List)
		 */
		public AsyncSpec resourceTemplates(ResourceTemplate... resourceTemplates) {
			for (ResourceTemplate resourceTemplate : resourceTemplates) {
				this.resourceTemplates.add(resourceTemplate);
			}
			return this;
		}

		/**
		 * 使用Map注册多个提示词及其处理器。
		 * 当提示词是动态生成或从配置源加载时，此方法很有用。
		 *
		 * 使用示例：
		 * <pre>{@code
		 * Map<String, PromptRegistration> prompts = new HashMap<>();
		 * .prompts(Map.of("analysis", new McpServerFeatures.AsyncPromptRegistration(
		 *     new Prompt("analysis", "代码分析模板"),
		 *     request -> Mono.just(new GetPromptResult(generateAnalysisPrompt(request)))
		 * )));
		 * }</pre>
		 * 
		 * @param prompts 提示词名称到注册的映射，不能为null
		 * @return 当前构建器实例，用于方法链式调用
		 * @throws IllegalArgumentException 如果prompts为null
		 */
		public AsyncSpec prompts(Map<String, McpServerFeatures.AsyncPromptRegistration> prompts) {
			this.prompts.putAll(prompts);
			return this;
		}

		/**
		 * 使用List注册多个提示词及其处理器。
		 * 当需要从集合中批量添加提示词时，此方法很有用。
		 *
		 * @param prompts 提示词注册列表，不能为null
		 * @return 当前构建器实例，用于方法链式调用
		 * @throws IllegalArgumentException 如果prompts为null
		 * @see #prompts(McpServerFeatures.AsyncPromptRegistration...)
		 */
		public AsyncSpec prompts(List<McpServerFeatures.AsyncPromptRegistration> prompts) {
			for (McpServerFeatures.AsyncPromptRegistration prompt : prompts) {
				this.prompts.put(prompt.prompt().name(), prompt);
			}
			return this;
		}

		/**
		 * 使用可变参数注册多个提示词及其处理器。
		 * 此方法提供了一种便捷的方式来内联注册多个提示词。
		 *
		 * 使用示例：
		 * <pre>{@code
		 * .prompts(
		 *     new McpServerFeatures.AsyncPromptRegistration(analysisPrompt, analysisHandler),
		 *     new McpServerFeatures.AsyncPromptRegistration(summaryPrompt, summaryHandler),
		 *     new McpServerFeatures.AsyncPromptRegistration(reviewPrompt, reviewHandler)
		 * )
		 * }</pre>
		 * 
		 * @param prompts 要添加的提示词注册列表，不能为null
		 * @return 当前构建器实例，用于方法链式调用
		 * @throws IllegalArgumentException 如果prompts为null
		 */
		public AsyncSpec prompts(McpServerFeatures.AsyncPromptRegistration... prompts) {
			for (McpServerFeatures.AsyncPromptRegistration prompt : prompts) {
				this.prompts.put(prompt.prompt().name(), prompt);
			}
			return this;
		}

		/**
		 * 注册一个在根资源列表变更时会被通知的消费者。
		 * 这对于动态更新资源可用性很有用，例如当新文件被添加或删除时。
		 *
		 * @param consumer 要注册的消费者，不能为null
		 * @return 当前构建器实例，用于方法链式调用
		 * @throws IllegalArgumentException 如果consumer为null
		 */
		public AsyncSpec rootsChangeConsumer(Function<List<McpSchema.Root>, Mono<Void>> consumer) {
			Assert.notNull(consumer, "消费者不能为null");
			this.rootsChangeConsumers.add(consumer);
			return this;
		}

		/**
		 * 注册多个在根资源列表变更时会被通知的消费者。
		 * 当需要一次注册多个消费者时，此方法很有用。
		 *
		 * @param consumers 要注册的消费者列表，不能为null
		 * @return 当前构建器实例，用于方法链式调用
		 * @throws IllegalArgumentException 如果consumers为null
		 */
		public AsyncSpec rootsChangeConsumers(List<Function<List<McpSchema.Root>, Mono<Void>>> consumers) {
			Assert.notNull(consumers, "消费者列表不能为null");
			this.rootsChangeConsumers.addAll(consumers);
			return this;
		}

		/**
		 * 使用可变参数注册多个在根资源列表变更时会被通知的消费者。
		 * 此方法提供了一种便捷的方式来内联注册多个消费者。
		 *
		 * @param consumers 要注册的消费者列表，不能为null
		 * @return 当前构建器实例，用于方法链式调用
		 * @throws IllegalArgumentException 如果consumers为null
		 */
		public AsyncSpec rootsChangeConsumers(
				@SuppressWarnings("unchecked") Function<List<McpSchema.Root>, Mono<Void>>... consumers) {
			for (Function<List<McpSchema.Root>, Mono<Void>> consumer : consumers) {
				this.rootsChangeConsumers.add(consumer);
			}
			return this;
		}

		/**
		 * 构建一个提供非阻塞操作的异步MCP服务器。
		 *
		 * @return 使用此构建器设置配置的新{@link McpAsyncServer}实例
		 */
		public McpAsyncServer build() {
			return new McpAsyncServer(this.transport,
					new McpServerFeatures.Async(this.serverInfo, this.serverCapabilities, this.tools, this.resources,
							this.resourceTemplates, this.prompts, this.rootsChangeConsumers));
		}

	}

	/**
	 * 同步服务器规范类。
	 * 用于配置和构建同步MCP服务器的所有必要参数。
	 * 同步服务器按顺序处理请求，适合简单场景。
	 */
	class SyncSpec {

		/**
		 * 默认的服务器信息配置
		 */
		private static final McpSchema.Implementation DEFAULT_SERVER_INFO = new McpSchema.Implementation("mcp-server",
				"1.0.0");

		/**
		 * MCP通信的传输层实现
		 */
		private final ServerMcpTransport transport;

		/**
		 * 服务器实现信息，包含名称和版本
		 */
		private McpSchema.Implementation serverInfo = DEFAULT_SERVER_INFO;

		/**
		 * 服务器能力配置，定义服务器支持的功能特性
		 */
		private McpSchema.ServerCapabilities serverCapabilities;

		/**
		 * MCP服务器暴露的工具列表。
		 * 工具允许AI模型与外部系统交互，例如查询数据库、调用API或执行计算。
		 * 每个工具都有唯一的名称和描述其功能的元数据。
		 */
		private final List<McpServerFeatures.SyncToolRegistration> tools = new ArrayList<>();

		/**
		 * MCP服务器暴露的资源映射。
		 * 资源允许服务器共享数据以为AI模型提供上下文，如文件、数据库模式或应用特定信息。
		 * 每个资源都由URI唯一标识。
		 */
		private final Map<String, McpServerFeatures.SyncResourceRegistration> resources = new HashMap<>();

		/**
		 * 资源模板列表，定义了动态资源访问的模式
		 */
		private final List<ResourceTemplate> resourceTemplates = new ArrayList<>();

		/**
		 * MCP服务器暴露的提示词模板映射。
		 * 提示词允许服务器提供结构化的消息和指令，用于与AI模型交互。
		 * 客户端可以发现可用的提示词，获取其内容，并提供参数来自定义它们。
		 */
		private final Map<String, McpServerFeatures.SyncPromptRegistration> prompts = new HashMap<>();

		/**
		 * 根资源变更的监听器列表
		 */
		private final List<Consumer<List<McpSchema.Root>>> rootsChangeConsumers = new ArrayList<>();

		/**
		 * 构造函数
		 * @param transport MCP通信的传输层实现
		 * @throws IllegalArgumentException 如果transport为null
		 */
		private SyncSpec(ServerMcpTransport transport) {
			Assert.notNull(transport, "传输层不能为null");
			this.transport = transport;
		}

		/**
		 * 设置服务器实现信息。
		 * 这些信息将在连接初始化期间与客户端共享，有助于版本兼容性、调试和服务器识别。
		 * 
		 * @param serverInfo 服务器实现详情，包括名称和版本
		 * @return 当前构建器实例，用于方法链式调用
		 * @throws IllegalArgumentException 如果serverInfo为null
		 */
		public SyncSpec serverInfo(McpSchema.Implementation serverInfo) {
			Assert.notNull(serverInfo, "服务器信息不能为null");
			this.serverInfo = serverInfo;
			return this;
		}

		/**
		 * 使用名称和版本字符串设置服务器实现信息。
		 * 这是{@link #serverInfo(McpSchema.Implementation)}方法的便捷替代方案。
		 * 
		 * @param name 服务器名称，不能为null或空
		 * @param version 服务器版本，不能为null或空
		 * @return 当前构建器实例，用于方法链式调用
		 * @throws IllegalArgumentException 如果name或version为null或空
		 * @see #serverInfo(McpSchema.Implementation)
		 */
		public SyncSpec serverInfo(String name, String version) {
			Assert.hasText(name, "名称不能为null或空");
			Assert.hasText(version, "版本不能为null或空");
			this.serverInfo = new McpSchema.Implementation(name, version);
			return this;
		}

		/**
		 * 设置服务器能力配置。
		 * 这些能力将在连接初始化期间向客户端公布。能力定义了服务器支持的功能，例如：
		 * <ul>
		 * <li>工具执行</li>
		 * <li>资源访问</li>
		 * <li>提示词处理</li>
		 * <li>流式响应</li>
		 * <li>批量操作</li>
		 * </ul>
		 * @param serverCapabilities The server capabilities configuration. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if serverCapabilities is null
		 */
		public SyncSpec capabilities(McpSchema.ServerCapabilities serverCapabilities) {
			this.serverCapabilities = serverCapabilities;
			return this;
		}

		/**
		 * 添加单个工具及其实现处理器到服务器。
		 * 这是一个便捷方法，无需显式创建SyncToolRegistration。
		 *
		 * 使用示例：
		 * <pre>{@code
		 * .tool(
		 *     new Tool("calculator", "执行计算操作", schema),
		 *     args -> new CallToolResult("结果: " + calculate(args))
		 * )
		 * }</pre>
		 * 
		 * @param tool 工具定义，包括名称、描述和模式
		 * @param handler 实现工具逻辑的函数
		 * @return 当前构建器实例，用于方法链式调用
		 * @throws IllegalArgumentException 如果tool或handler为null
		 */
		public SyncSpec tool(McpSchema.Tool tool, Function<Map<String, Object>, McpSchema.CallToolResult> handler) {
			Assert.notNull(tool, "工具不能为null");
			Assert.notNull(handler, "处理器不能为null");

			this.tools.add(new McpServerFeatures.SyncToolRegistration(tool, handler));

			return this;
		}

		/**
		 * 使用List添加多个工具及其处理器到服务器。
		 * 当工具是动态生成或从配置源加载时，此方法很有用。
		 *
		 * @param toolRegistrations 要添加的工具注册列表，不能为null
		 * @return 当前构建器实例，用于方法链式调用
		 * @throws IllegalArgumentException 如果toolRegistrations为null
		 * @see #tools(McpServerFeatures.SyncToolRegistration...)
		 */
		public SyncSpec tools(List<McpServerFeatures.SyncToolRegistration> toolRegistrations) {
			Assert.notNull(toolRegistrations, "工具处理器列表不能为null");
			this.tools.addAll(toolRegistrations);
			return this;
		}

		/**
		 * 使用可变参数添加多个工具及其处理器到服务器。
		 * 此方法提供了一种便捷的方式来内联注册多个工具。
		 *
		 * 使用示例：
		 * <pre>{@code
		 * .tools(
		 *     new ToolRegistration(calculatorTool, calculatorHandler),
		 *     new ToolRegistration(weatherTool, weatherHandler),
		 *     new ToolRegistration(fileManagerTool, fileManagerHandler)
		 * )
		 * }</pre>
		 * 
		 * @param toolRegistrations 要添加的工具注册列表，不能为null
		 * @return 当前构建器实例，用于方法链式调用
		 * @throws IllegalArgumentException 如果toolRegistrations为null
		 * @see #tools(List)
		 */
		public SyncSpec tools(McpServerFeatures.SyncToolRegistration... toolRegistrations) {
			for (McpServerFeatures.SyncToolRegistration tool : toolRegistrations) {
				this.tools.add(tool);
			}
			return this;
		}

		/**
		 * Registers multiple resources with their handlers using a Map. This method is
		 * useful when resources are dynamically generated or loaded from a configuration
		 * source.
		 * @param resourceRegsitrations Map of resource name to registration. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceRegsitrations is null
		 * @see #resources(McpServerFeatures.SyncResourceRegistration...)
		 */
		public SyncSpec resources(Map<String, McpServerFeatures.SyncResourceRegistration> resourceRegsitrations) {
			Assert.notNull(resourceRegsitrations, "Resource handlers map must not be null");
			this.resources.putAll(resourceRegsitrations);
			return this;
		}

		/**
		 * Registers multiple resources with their handlers using a List. This method is
		 * useful when resources need to be added in bulk from a collection.
		 * @param resourceRegsitrations List of resource registrations. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceRegsitrations is null
		 * @see #resources(McpServerFeatures.SyncResourceRegistration...)
		 */
		public SyncSpec resources(List<McpServerFeatures.SyncResourceRegistration> resourceRegsitrations) {
			Assert.notNull(resourceRegsitrations, "Resource handlers list must not be null");
			for (McpServerFeatures.SyncResourceRegistration resource : resourceRegsitrations) {
				this.resources.put(resource.resource().uri(), resource);
			}
			return this;
		}

		/**
		 * Registers multiple resources with their handlers using varargs. This method
		 * provides a convenient way to register multiple resources inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .resources(
		 *     new ResourceRegistration(fileResource, fileHandler),
		 *     new ResourceRegistration(dbResource, dbHandler),
		 *     new ResourceRegistration(apiResource, apiHandler)
		 * )
		 * }</pre>
		 * @param resourceRegistrations The resource registrations to add. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceRegistrations is null
		 */
		public SyncSpec resources(McpServerFeatures.SyncResourceRegistration... resourceRegistrations) {
			Assert.notNull(resourceRegistrations, "Resource handlers list must not be null");
			for (McpServerFeatures.SyncResourceRegistration resource : resourceRegistrations) {
				this.resources.put(resource.resource().uri(), resource);
			}
			return this;
		}

		/**
		 * Sets the resource templates that define patterns for dynamic resource access.
		 * Templates use URI patterns with placeholders that can be filled at runtime.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .resourceTemplates(
		 *     new ResourceTemplate("file://{path}", "Access files by path"),
		 *     new ResourceTemplate("db://{table}/{id}", "Access database records")
		 * )
		 * }</pre>
		 * @param resourceTemplates List of resource templates. If null, clears existing
		 * templates.
		 * @return This builder instance for method chaining
		 * @see #resourceTemplates(ResourceTemplate...)
		 */
		public SyncSpec resourceTemplates(List<ResourceTemplate> resourceTemplates) {
			this.resourceTemplates.addAll(resourceTemplates);
			return this;
		}

		/**
		 * Sets the resource templates using varargs for convenience. This is an
		 * alternative to {@link #resourceTemplates(List)}.
		 * @param resourceTemplates The resource templates to set.
		 * @return This builder instance for method chaining
		 * @see #resourceTemplates(List)
		 */
		public SyncSpec resourceTemplates(ResourceTemplate... resourceTemplates) {
			for (ResourceTemplate resourceTemplate : resourceTemplates) {
				this.resourceTemplates.add(resourceTemplate);
			}
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using a Map. This method is
		 * useful when prompts are dynamically generated or loaded from a configuration
		 * source.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * Map<String, PromptRegistration> prompts = new HashMap<>();
		 * prompts.put("analysis", new PromptRegistration(
		 *     new Prompt("analysis", "Code analysis template"),
		 *     request -> new GetPromptResult(generateAnalysisPrompt(request))
		 * ));
		 * .prompts(prompts)
		 * }</pre>
		 * @param prompts Map of prompt name to registration. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 */
		public SyncSpec prompts(Map<String, McpServerFeatures.SyncPromptRegistration> prompts) {
			this.prompts.putAll(prompts);
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using a List. This method is
		 * useful when prompts need to be added in bulk from a collection.
		 * @param prompts List of prompt registrations. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 * @see #prompts(McpServerFeatures.SyncPromptRegistration...)
		 */
		public SyncSpec prompts(List<McpServerFeatures.SyncPromptRegistration> prompts) {
			for (McpServerFeatures.SyncPromptRegistration prompt : prompts) {
				this.prompts.put(prompt.prompt().name(), prompt);
			}
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using varargs. This method
		 * provides a convenient way to register multiple prompts inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .prompts(
		 *     new PromptRegistration(analysisPrompt, analysisHandler),
		 *     new PromptRegistration(summaryPrompt, summaryHandler),
		 *     new PromptRegistration(reviewPrompt, reviewHandler)
		 * )
		 * }</pre>
		 * @param prompts The prompt registrations to add. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 */
		public SyncSpec prompts(McpServerFeatures.SyncPromptRegistration... prompts) {
			for (McpServerFeatures.SyncPromptRegistration prompt : prompts) {
				this.prompts.put(prompt.prompt().name(), prompt);
			}
			return this;
		}

		/**
		 * Registers a consumer that will be notified when the list of roots changes. This
		 * is useful for updating resource availability dynamically, such as when new
		 * files are added or removed.
		 * @param consumer The consumer to register. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if consumer is null
		 */
		public SyncSpec rootsChangeConsumer(Consumer<List<McpSchema.Root>> consumer) {
			Assert.notNull(consumer, "Consumer must not be null");
			this.rootsChangeConsumers.add(consumer);
			return this;
		}

		/**
		 * Registers multiple consumers that will be notified when the list of roots
		 * changes. This method is useful when multiple consumers need to be registered at
		 * once.
		 * @param consumers The list of consumers to register. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if consumers is null
		 */
		public SyncSpec rootsChangeConsumers(List<Consumer<List<McpSchema.Root>>> consumers) {
			Assert.notNull(consumers, "Consumers list must not be null");
			this.rootsChangeConsumers.addAll(consumers);
			return this;
		}

		/**
		 * Registers multiple consumers that will be notified when the list of roots
		 * changes using varargs. This method provides a convenient way to register
		 * multiple consumers inline.
		 * @param consumers The consumers to register. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if consumers is null
		 */
		public SyncSpec rootsChangeConsumers(Consumer<List<McpSchema.Root>>... consumers) {
			for (Consumer<List<McpSchema.Root>> consumer : consumers) {
				this.rootsChangeConsumers.add(consumer);
			}
			return this;
		}

		/**
		 * Builds a synchronous MCP server that provides blocking operations.
		 * @return A new instance of {@link McpSyncServer} configured with this builder's
		 * settings
		 */
		public McpSyncServer build() {
			McpServerFeatures.Sync syncFeatures = new McpServerFeatures.Sync(this.serverInfo, this.serverCapabilities,
					this.tools, this.resources, this.resourceTemplates, this.prompts, this.rootsChangeConsumers);
			return new McpSyncServer(
					new McpAsyncServer(this.transport, McpServerFeatures.Async.fromSync(syncFeatures)));
		}

	}

}
