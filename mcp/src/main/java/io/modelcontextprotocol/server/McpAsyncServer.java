/*
 * Copyright 2024-2024 the original author or authors.
 */

// 异步MCP服务器实现类，提供基于Project Reactor的异步通信功能。
package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.spec.DefaultMcpSession;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import io.modelcontextprotocol.spec.DefaultMcpSession.NotificationHandler;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Model Context Protocol (MCP) 异步服务器实现类，使用 Project Reactor 的 Mono 和 Flux 类型提供异步通信功能。
 *
 * <p>
 * 该服务器实现了 MCP 规范，使 AI 模型能够通过标准化接口暴露工具、资源和提示词。主要特性包括：
 * <ul>
 * <li>使用响应式编程模式进行异步通信
 * <li>动态工具注册和管理
 * <li>基于 URI 的资源处理
 * <li>提示词模板管理
 * <li>实时客户端状态变更通知
 * <li>可配置严重级别的结构化日志
 * <li>支持客户端 AI 模型采样
 * </ul>
 *
 * <p>
 * 服务器遵循以下生命周期：
 * <ol>
 * <li>初始化 - 接受客户端连接并协商功能
 * <li>正常运行 - 处理客户端请求并发送通知
 * <li>优雅关闭 - 确保连接正常终止
 * </ol>
 *
 * <p>
 * 该实现使用 Project Reactor 进行非阻塞操作，适用于高吞吐量场景和响应式应用。所有操作都返回
 * Mono 或 Flux 类型，可以组合成响应式管道。
 *
 * <p>
 * 服务器支持通过 {@link #addTool}、{@link #addResource} 和 {@link #addPrompt} 等方法在运行时修改其功能，
 * 当配置为这样做时，会自动通知连接的客户端变更。
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @see McpServer
 * @see McpSchema
 * @see DefaultMcpSession
 */
public class McpAsyncServer {

    // 日志记录器实例
    private static final Logger logger = LoggerFactory.getLogger(McpAsyncServer.class);

    /**
     * MCP 会话实现，管理客户端和服务器之间的双向 JSON-RPC 通信。
     */
    private final DefaultMcpSession mcpSession;

    // 服务器传输层实现
    private final ServerMcpTransport transport;

    // 服务器功能定义
    private final McpSchema.ServerCapabilities serverCapabilities;

    // 服务器实现信息
    private final McpSchema.Implementation serverInfo;

    // 客户端功能定义
    private McpSchema.ClientCapabilities clientCapabilities;

    // 客户端实现信息
    private McpSchema.Implementation clientInfo;

    /**
     * 线程安全的工具处理器列表，支持运行时修改。
     */
    private final CopyOnWriteArrayList<McpServerFeatures.AsyncToolRegistration> tools = new CopyOnWriteArrayList<>();

    // 资源模板列表
    private final CopyOnWriteArrayList<McpSchema.ResourceTemplate> resourceTemplates = new CopyOnWriteArrayList<>();

    // 资源处理器映射表，键为资源URI
    private final ConcurrentHashMap<String, McpServerFeatures.AsyncResourceRegistration> resources = new ConcurrentHashMap<>();

    // 提示词处理器映射表，键为提示词名称
    private final ConcurrentHashMap<String, McpServerFeatures.AsyncPromptRegistration> prompts = new ConcurrentHashMap<>();

    // 最小日志级别，低于此级别的日志消息将被过滤
    private LoggingLevel minLoggingLevel = LoggingLevel.DEBUG;

    /**
     * 支持的协议版本列表。
     */
    private List<String> protocolVersions = List.of(McpSchema.LATEST_PROTOCOL_VERSION);

    /**
     * 创建新的 McpAsyncServer 实例。
     * @param mcpTransport MCP 通信的传输层实现
     * @param features MCP 服务器支持的功能
     */
    McpAsyncServer(ServerMcpTransport mcpTransport, McpServerFeatures.Async features) {
        // 初始化服务器信息和功能
        this.serverInfo = features.serverInfo();
        this.serverCapabilities = features.serverCapabilities();
        this.tools.addAll(features.tools());
        this.resources.putAll(features.resources());
        this.resourceTemplates.addAll(features.resourceTemplates());
        this.prompts.putAll(features.prompts());

        // 创建请求处理器映射
        Map<String, DefaultMcpSession.RequestHandler<?>> requestHandlers = new HashMap<>();

        // 初始化标准 MCP 方法的请求处理器
        requestHandlers.put(McpSchema.METHOD_INITIALIZE, asyncInitializeRequestHandler());

        // Ping 方法必须返回空数据，但不能是 NULL 响应
        requestHandlers.put(McpSchema.METHOD_PING, (params) -> Mono.just(""));

        // 如果启用了工具功能，添加工具 API 处理器
        if (this.serverCapabilities.tools() != null) {
            requestHandlers.put(McpSchema.METHOD_TOOLS_LIST, toolsListRequestHandler());
            requestHandlers.put(McpSchema.METHOD_TOOLS_CALL, toolsCallRequestHandler());
        }

        // 如果提供了资源功能，添加资源 API 处理器
        if (this.serverCapabilities.resources() != null) {
            requestHandlers.put(McpSchema.METHOD_RESOURCES_LIST, resourcesListRequestHandler());
            requestHandlers.put(McpSchema.METHOD_RESOURCES_READ, resourcesReadRequestHandler());
            requestHandlers.put(McpSchema.METHOD_RESOURCES_TEMPLATES_LIST, resourceTemplateListRequestHandler());
        }

        // 如果存在提示词功能，添加提示词 API 处理器
        if (this.serverCapabilities.prompts() != null) {
            requestHandlers.put(McpSchema.METHOD_PROMPT_LIST, promptsListRequestHandler());
            requestHandlers.put(McpSchema.METHOD_PROMPT_GET, promptsGetRequestHandler());
        }

        // 如果启用了日志功能，添加日志 API 处理器
        if (this.serverCapabilities.logging() != null) {
            requestHandlers.put(McpSchema.METHOD_LOGGING_SET_LEVEL, setLoggerRequestHandler());
        }

        // 创建通知处理器映射
        Map<String, NotificationHandler> notificationHandlers = new HashMap<>();

        // 初始化完成通知处理器
        notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_INITIALIZED, (params) -> Mono.empty());

        // 获取根列表变更消费者列表
        List<Function<List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers = features.rootsChangeConsumers();

        // 如果没有提供消费者，使用默认的日志记录消费者
        if (Utils.isEmpty(rootsChangeConsumers)) {
            rootsChangeConsumers = List.of((roots) -> Mono.fromRunnable(() -> logger
                .warn("Roots list changed notification, but no consumers provided. Roots list changed: {}", roots)));
        }

        // 添加根列表变更通知处理器
        notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_ROOTS_LIST_CHANGED,
                asyncRootsListChangedNotificationHandler(rootsChangeConsumers));

        // 初始化传输层和会话
        this.transport = mcpTransport;
        this.mcpSession = new DefaultMcpSession(Duration.ofSeconds(10), mcpTransport, requestHandlers,
                notificationHandlers);
    }

    // ---------------------------------------
    // 生命周期管理
    // ---------------------------------------
    /**
     * 创建初始化请求处理器。
     * 处理客户端的初始化请求，协商协议版本和功能。
     * @return 初始化请求处理器
     */
    private DefaultMcpSession.RequestHandler<McpSchema.InitializeResult> asyncInitializeRequestHandler() {
        return params -> {
            // 解析初始化请求
            McpSchema.InitializeRequest initializeRequest = transport.unmarshalFrom(params,
                    new TypeReference<McpSchema.InitializeRequest>() {
                    });
            this.clientCapabilities = initializeRequest.capabilities();
            this.clientInfo = initializeRequest.clientInfo();
            logger.info("Client initialize request - Protocol: {}, Capabilities: {}, Info: {}",
                    initializeRequest.protocolVersion(), initializeRequest.capabilities(),
                    initializeRequest.clientInfo());

            // 服务器必须响应它支持的最高协议版本，如果不支持请求的版本
            String serverProtocolVersion = this.protocolVersions.get(this.protocolVersions.size() - 1);

            // 如果服务器支持请求的协议版本，必须使用相同的版本响应
            if (this.protocolVersions.contains(initializeRequest.protocolVersion())) {
                serverProtocolVersion = initializeRequest.protocolVersion();
            }
            else {
                logger.warn(
                        "Client requested unsupported protocol version: {}, so the server will sugggest the {} version instead",
                        initializeRequest.protocolVersion(), serverProtocolVersion);
            }

            // 返回初始化结果
            return Mono.just(new McpSchema.InitializeResult(serverProtocolVersion, this.serverCapabilities,
                    this.serverInfo, null));
        };
    }

    /**
     * 获取服务器功能定义。
     * @return 服务器功能
     */
    public McpSchema.ServerCapabilities getServerCapabilities() {
        return this.serverCapabilities;
    }

    /**
     * 获取服务器实现信息。
     * @return 服务器实现详情
     */
    public McpSchema.Implementation getServerInfo() {
        return this.serverInfo;
    }

    /**
     * 获取客户端功能定义。
     * @return 客户端功能
     */
    public ClientCapabilities getClientCapabilities() {
        return this.clientCapabilities;
    }

    /**
     * 获取客户端实现信息。
     * @return 客户端实现详情
     */
    public McpSchema.Implementation getClientInfo() {
        return this.clientInfo;
    }

    /**
     * 优雅关闭服务器，允许正在进行的操作完成。
     * @return 服务器关闭完成时的 Mono
     */
    public Mono<Void> closeGracefully() {
        return this.mcpSession.closeGracefully();
    }

    /**
     * 立即关闭服务器。
     */
    public void close() {
        this.mcpSession.close();
    }

    // 用于类型引用的静态字段
    private static final TypeReference<McpSchema.ListRootsResult> LIST_ROOTS_RESULT_TYPE_REF = new TypeReference<>() {
    };

    /**
     * 获取客户端提供的所有根列表。
     * @return 包含根列表结果的 Mono
     */
    public Mono<McpSchema.ListRootsResult> listRoots() {
        return this.listRoots(null);
    }

    /**
     * 获取服务器提供的分页根列表。
     * @param cursor 来自之前列表请求的可选分页游标
     * @return 包含根列表结果的 Mono
     */
    public Mono<McpSchema.ListRootsResult> listRoots(String cursor) {
        return this.mcpSession.sendRequest(McpSchema.METHOD_ROOTS_LIST, new McpSchema.PaginatedRequest(cursor),
                LIST_ROOTS_RESULT_TYPE_REF);
    }

    /**
     * 创建根列表变更通知处理器。
     * @param rootsChangeConsumers 根列表变更消费者列表
     * @return 根列表变更通知处理器
     */
    private NotificationHandler asyncRootsListChangedNotificationHandler(
            List<Function<List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers) {
        return params -> listRoots().flatMap(listRootsResult -> Flux.fromIterable(rootsChangeConsumers)
            .flatMap(consumer -> consumer.apply(listRootsResult.roots()))
            .onErrorResume(error -> {
                logger.error("Error handling roots list change notification", error);
                return Mono.empty();
            })
            .then());
    }

    // ---------------------------------------
    // 工具管理
    // ---------------------------------------

    /**
     * 在运行时添加新的工具注册。
     * @param toolRegistration 要添加的工具注册
     * @return 客户端通知完成时的 Mono
     */
    public Mono<Void> addTool(McpServerFeatures.AsyncToolRegistration toolRegistration) {
        // 参数验证
        if (toolRegistration == null) {
            return Mono.error(new McpError("Tool registration must not be null"));
        }
        if (toolRegistration.tool() == null) {
            return Mono.error(new McpError("Tool must not be null"));
        }
        if (toolRegistration.call() == null) {
            return Mono.error(new McpError("Tool call handler must not be null"));
        }
        if (this.serverCapabilities.tools() == null) {
            return Mono.error(new McpError("Server must be configured with tool capabilities"));
        }

        return Mono.defer(() -> {
            // 检查重复的工具名称
            if (this.tools.stream().anyMatch(th -> th.tool().name().equals(toolRegistration.tool().name()))) {
                return Mono
                    .error(new McpError("Tool with name '" + toolRegistration.tool().name() + "' already exists"));
            }

            // 添加工具并记录日志
            this.tools.add(toolRegistration);
            logger.debug("Added tool handler: {}", toolRegistration.tool().name());

            // 如果启用了列表变更通知，发送通知
            if (this.serverCapabilities.tools().listChanged()) {
                return notifyToolsListChanged();
            }
            return Mono.empty();
        });
    }

    /**
     * 在运行时移除工具处理器。
     * @param toolName 要移除的工具处理器名称
     * @return 客户端通知完成时的 Mono
     */
    public Mono<Void> removeTool(String toolName) {
        // 参数验证
        if (toolName == null) {
            return Mono.error(new McpError("Tool name must not be null"));
        }
        if (this.serverCapabilities.tools() == null) {
            return Mono.error(new McpError("Server must be configured with tool capabilities"));
        }

        return Mono.defer(() -> {
            // 移除工具并记录日志
            boolean removed = this.tools.removeIf(toolRegistration -> toolRegistration.tool().name().equals(toolName));
            if (removed) {
                logger.debug("Removed tool handler: {}", toolName);
                // 如果启用了列表变更通知，发送通知
                if (this.serverCapabilities.tools().listChanged()) {
                    return notifyToolsListChanged();
                }
                return Mono.empty();
            }
            return Mono.error(new McpError("Tool with name '" + toolName + "' not found"));
        });
    }

    /**
     * 通知客户端可用工具列表已更改。
     * @return 所有客户端通知完成时的 Mono
     */
    public Mono<Void> notifyToolsListChanged() {
        return this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED, null);
    }

    /**
     * 创建工具列表请求处理器。
     * @return 工具列表请求处理器
     */
    private DefaultMcpSession.RequestHandler<McpSchema.ListToolsResult> toolsListRequestHandler() {
        return params -> {
            List<Tool> tools = this.tools.stream().map(McpServerFeatures.AsyncToolRegistration::tool).toList();
            return Mono.just(new McpSchema.ListToolsResult(tools, null));
        };
    }

    /**
     * 创建工具调用请求处理器。
     * @return 工具调用请求处理器
     */
    private DefaultMcpSession.RequestHandler<CallToolResult> toolsCallRequestHandler() {
        return params -> {
            // 解析工具调用请求
            McpSchema.CallToolRequest callToolRequest = transport.unmarshalFrom(params,
                    new TypeReference<McpSchema.CallToolRequest>() {
                    });

            // 查找并执行工具
            Optional<McpServerFeatures.AsyncToolRegistration> toolRegistration = this.tools.stream()
                .filter(tr -> callToolRequest.name().equals(tr.tool().name()))
                .findAny();

            if (toolRegistration.isEmpty()) {
                return Mono.error(new McpError("Tool not found: " + callToolRequest.name()));
            }

            return toolRegistration.map(tool -> tool.call().apply(callToolRequest.arguments()))
                .orElse(Mono.error(new McpError("Tool not found: " + callToolRequest.name())));
        };
    }

    // ---------------------------------------
    // 资源管理
    // ---------------------------------------

    /**
     * 在运行时添加新的资源处理器。
     * @param resourceHandler 要添加的资源处理器
     * @return 客户端通知完成时的 Mono
     */
    public Mono<Void> addResource(McpServerFeatures.AsyncResourceRegistration resourceHandler) {
        // 参数验证
        if (resourceHandler == null || resourceHandler.resource() == null) {
            return Mono.error(new McpError("Resource must not be null"));
        }

        if (this.serverCapabilities.resources() == null) {
            return Mono.error(new McpError("Server must be configured with resource capabilities"));
        }

        return Mono.defer(() -> {
            // 检查重复的资源 URI
            if (this.resources.putIfAbsent(resourceHandler.resource().uri(), resourceHandler) != null) {
                return Mono
                    .error(new McpError("Resource with URI '" + resourceHandler.resource().uri() + "' already exists"));
            }
            // 记录日志并发送通知
            logger.debug("Added resource handler: {}", resourceHandler.resource().uri());
            if (this.serverCapabilities.resources().listChanged()) {
                return notifyResourcesListChanged();
            }
            return Mono.empty();
        });
    }

    /**
     * 在运行时移除资源处理器。
     * @param resourceUri 要移除的资源处理器 URI
     * @return 客户端通知完成时的 Mono
     */
    public Mono<Void> removeResource(String resourceUri) {
        // 参数验证
        if (resourceUri == null) {
            return Mono.error(new McpError("Resource URI must not be null"));
        }
        if (this.serverCapabilities.resources() == null) {
            return Mono.error(new McpError("Server must be configured with resource capabilities"));
        }

        return Mono.defer(() -> {
            // 移除资源并记录日志
            McpServerFeatures.AsyncResourceRegistration removed = this.resources.remove(resourceUri);
            if (removed != null) {
                logger.debug("Removed resource handler: {}", resourceUri);
                // 发送通知
                if (this.serverCapabilities.resources().listChanged()) {
                    return notifyResourcesListChanged();
                }
                return Mono.empty();
            }
            return Mono.error(new McpError("Resource with URI '" + resourceUri + "' not found"));
        });
    }

    /**
     * 通知客户端可用资源列表已更改。
     * @return 所有客户端通知完成时的 Mono
     */
    public Mono<Void> notifyResourcesListChanged() {
        return this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED, null);
    }

    /**
     * 创建资源列表请求处理器。
     * @return 资源列表请求处理器
     */
    private DefaultMcpSession.RequestHandler<McpSchema.ListResourcesResult> resourcesListRequestHandler() {
        return params -> {
            var resourceList = this.resources.values()
                .stream()
                .map(McpServerFeatures.AsyncResourceRegistration::resource)
                .toList();
            return Mono.just(new McpSchema.ListResourcesResult(resourceList, null));
        };
    }

    /**
     * 创建资源模板列表请求处理器。
     * @return 资源模板列表请求处理器
     */
    private DefaultMcpSession.RequestHandler<McpSchema.ListResourceTemplatesResult> resourceTemplateListRequestHandler() {
        return params -> Mono.just(new McpSchema.ListResourceTemplatesResult(this.resourceTemplates, null));
    }

    /**
     * 创建资源读取请求处理器。
     * @return 资源读取请求处理器
     */
    private DefaultMcpSession.RequestHandler<McpSchema.ReadResourceResult> resourcesReadRequestHandler() {
        return params -> {
            // 解析资源读取请求
            McpSchema.ReadResourceRequest resourceRequest = transport.unmarshalFrom(params,
                    new TypeReference<McpSchema.ReadResourceRequest>() {
                    });
            var resourceUri = resourceRequest.uri();
            // 查找并执行资源处理器
            McpServerFeatures.AsyncResourceRegistration registration = this.resources.get(resourceUri);
            if (registration != null) {
                return registration.readHandler().apply(resourceRequest);
            }
            return Mono.error(new McpError("Resource not found: " + resourceUri));
        };
    }

    // ---------------------------------------
    // 提示词管理
    // ---------------------------------------

    /**
     * 在运行时添加新的提示词处理器。
     * @param promptRegistration 要添加的提示词处理器
     * @return 客户端通知完成时的 Mono
     */
    public Mono<Void> addPrompt(McpServerFeatures.AsyncPromptRegistration promptRegistration) {
        // 参数验证
        if (promptRegistration == null) {
            return Mono.error(new McpError("Prompt registration must not be null"));
        }
        if (this.serverCapabilities.prompts() == null) {
            return Mono.error(new McpError("Server must be configured with prompt capabilities"));
        }

        return Mono.defer(() -> {
            // 检查重复的提示词名称
            McpServerFeatures.AsyncPromptRegistration registration = this.prompts
                .putIfAbsent(promptRegistration.prompt().name(), promptRegistration);
            if (registration != null) {
                return Mono.error(
                        new McpError("Prompt with name '" + promptRegistration.prompt().name() + "' already exists"));
            }

            // 记录日志并发送通知
            logger.debug("Added prompt handler: {}", promptRegistration.prompt().name());

            if (this.serverCapabilities.prompts().listChanged()) {
                return notifyPromptsListChanged();
            }
            return Mono.empty();
        });
    }

    /**
     * 在运行时移除提示词处理器。
     * @param promptName 要移除的提示词处理器名称
     * @return 客户端通知完成时的 Mono
     */
    public Mono<Void> removePrompt(String promptName) {
        // 参数验证
        if (promptName == null) {
            return Mono.error(new McpError("Prompt name must not be null"));
        }
        if (this.serverCapabilities.prompts() == null) {
            return Mono.error(new McpError("Server must be configured with prompt capabilities"));
        }

        return Mono.defer(() -> {
            // 移除提示词并记录日志
            McpServerFeatures.AsyncPromptRegistration removed = this.prompts.remove(promptName);

            if (removed != null) {
                logger.debug("Removed prompt handler: {}", promptName);
                // 发送通知
                if (this.serverCapabilities.prompts().listChanged()) {
                    return this.notifyPromptsListChanged();
                }
                return Mono.empty();
            }
            return Mono.error(new McpError("Prompt with name '" + promptName + "' not found"));
        });
    }

    /**
     * 通知客户端可用提示词列表已更改。
     * @return 所有客户端通知完成时的 Mono
     */
    public Mono<Void> notifyPromptsListChanged() {
        return this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED, null);
    }

    /**
     * 创建提示词列表请求处理器。
     * @return 提示词列表请求处理器
     */
    private DefaultMcpSession.RequestHandler<McpSchema.ListPromptsResult> promptsListRequestHandler() {
        return params -> {
            var promptList = this.prompts.values()
                .stream()
                .map(McpServerFeatures.AsyncPromptRegistration::prompt)
                .toList();

            return Mono.just(new McpSchema.ListPromptsResult(promptList, null));
        };
    }

    /**
     * 创建提示词获取请求处理器。
     * @return 提示词获取请求处理器
     */
    private DefaultMcpSession.RequestHandler<McpSchema.GetPromptResult> promptsGetRequestHandler() {
        return params -> {
            // 解析提示词获取请求
            McpSchema.GetPromptRequest promptRequest = transport.unmarshalFrom(params,
                    new TypeReference<McpSchema.GetPromptRequest>() {
                    });

            // 查找并执行提示词处理器
            McpServerFeatures.AsyncPromptRegistration registration = this.prompts.get(promptRequest.name());
            if (registration == null) {
                return Mono.error(new McpError("Prompt not found: " + promptRequest.name()));
            }

            return registration.promptHandler().apply(promptRequest);
        };
    }

    // ---------------------------------------
    // 日志管理
    // ---------------------------------------

    /**
     * 向所有连接的客户端发送日志消息通知。低于当前最小日志级别的消息将被过滤。
     * @param loggingMessageNotification 要发送的日志消息
     * @return 通知发送完成时的 Mono
     */
    public Mono<Void> loggingNotification(LoggingMessageNotification loggingMessageNotification) {
        // 参数验证
        if (loggingMessageNotification == null) {
            return Mono.error(new McpError("Logging message must not be null"));
        }

        // 解析日志消息参数
        Map<String, Object> params = this.transport.unmarshalFrom(loggingMessageNotification,
                new TypeReference<Map<String, Object>>() {
                });

        // 过滤低于最小日志级别的消息
        if (loggingMessageNotification.level().level() < minLoggingLevel.level()) {
            return Mono.empty();
        }

        // 发送通知
        return this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_MESSAGE, params);
    }

    /**
     * 创建设置最小日志级别的请求处理器。
     * 低于此级别的消息将不会被发送。
     * @return 日志级别设置请求处理器
     */
    private DefaultMcpSession.RequestHandler<Void> setLoggerRequestHandler() {
        return params -> {
            this.minLoggingLevel = transport.unmarshalFrom(params, new TypeReference<LoggingLevel>() {
            });
            return Mono.empty();
        };
    }

    // ---------------------------------------
    // 采样
    // ---------------------------------------
    // 用于类型引用的静态字段
    private static final TypeReference<McpSchema.CreateMessageResult> CREATE_MESSAGE_RESULT_TYPE_REF = new TypeReference<>() {
    };

    /**
     * 使用客户端的采样功能创建新消息。Model Context Protocol (MCP) 提供了一种标准化的方式，
     * 使服务器能够通过客户端请求 LLM 采样（"补全"或"生成"）。这种流程允许客户端保持对模型访问、
     * 选择和权限的控制，同时使服务器能够利用 AI 功能—无需服务器 API 密钥。服务器可以请求基于文本
     * 或图像的交互，并可选地在提示词中包含来自 MCP 服务器的上下文。
     * @param createMessageRequest 创建新消息的请求
     * @return 消息创建完成时的 Mono
     * @throws McpError 如果客户端未初始化或不支持采样功能
     * @throws McpError 如果客户端不支持 createMessage 方法
     * @see McpSchema.CreateMessageRequest
     * @see McpSchema.CreateMessageResult
     * @see <a href=
     * "https://spec.modelcontextprotocol.io/specification/client/sampling/">Sampling
     * Specification</a>
     */
    public Mono<McpSchema.CreateMessageResult> createMessage(McpSchema.CreateMessageRequest createMessageRequest) {
        // 验证客户端状态
        if (this.clientCapabilities == null) {
            return Mono.error(new McpError("Client must be initialized. Call the initialize method first!"));
        }
        if (this.clientCapabilities.sampling() == null) {
            return Mono.error(new McpError("Client must be configured with sampling capabilities"));
        }
        // 发送创建消息请求
        return this.mcpSession.sendRequest(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, createMessageRequest,
                CREATE_MESSAGE_RESULT_TYPE_REF);
    }

    /**
     * 此方法为包私有，仅用于测试。不应由用户代码调用。
     * @param protocolVersions 客户端支持的协议版本列表
     */
    void setProtocolVersions(List<String> protocolVersions) {
        this.protocolVersions = protocolVersions;
    }
}