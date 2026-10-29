# Spring Boot Web：请求链路、校验、事务与异常

## 1. Spring Boot 启动与自动配置

Spring Boot 应用入口是带有 `@SpringBootApplication` 的主类，它组合了 `@SpringBootConfiguration`、`@EnableAutoConfiguration` 与 `@ComponentScan`。默认扫描主类所在包及其子包，所以主类应放在最外层包，Controller、Service、Mapper 才能被扫描到。

自动配置通过 `META-INF/spring.factories` 与 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（Spring Boot 2.7 起支持）加载配置类，再结合 `@ConditionalOnClass`、`@ConditionalOnMissingBean`、`@ConditionalOnProperty` 等条件决定是否生效。用户自定义的同类型 Bean 通常优先于自动配置，因为自动配置类大多带 `@ConditionalOnMissingBean`。

`application.yml` 与 `application-{profile}.yml` 共同组成配置，profile 由 `spring.profiles.active` 指定。配置优先级从高到低大致为：命令行参数、Java 系统属性、操作系统环境变量、`application-{profile}.yml`、`application.yml`。敏感信息应通过环境变量占位符 `${DB_PASSWORD}` 注入，不要写进仓库。

`@ConfigurationProperties(prefix = "app.ai")` 可以把一组配置绑定到 POJO，字段名采用 kebab-case 与驼峰映射，例如 `chat-base-url` 对应 `chatBaseUrl`。需要配合 `@Component` 或 `@EnableConfigurationProperties` 生效。相比散落的 `@Value`，配置类更利于集中校验与复用。

## 2. DispatcherServlet 请求链路

一个 HTTP 请求进入 Spring MVC 后大致经历：

1. Servlet 容器（Tomcat）接收连接，按 URL 找到对应的 Servlet，通常是 `DispatcherServlet`。
2. 依次经过 Filter 链，例如编码过滤器、认证过滤器、CORS 过滤器。
3. `DispatcherServlet` 查询 `HandlerMapping`，得到处理器执行链（HandlerExecutionChain），其中包含 Controller 方法与拦截器列表。
4. 依次执行拦截器的 `preHandle`，任一返回 false 则中断。
5. 由 `HandlerAdapter` 调用 Controller 方法，期间完成参数解析、数据绑定、类型转换、校验。
6. Controller 返回对象或视图名，`HandlerMethodReturnValueHandler` 与 `HttpMessageConverter` 把返回值写入响应；`@RestController` 的方法返回值通常由 Jackson 序列化为 JSON。
7. 执行拦截器的 `postHandle` 与 `afterCompletion`，异常则交给 `HandlerExceptionResolver`。

`Filter` 属于 Servlet 规范，在 `DispatcherServlet` 之前执行，能拿到原始请求但拿不到 Controller 方法信息；`HandlerInterceptor` 属于 Spring MVC，能拿到 HandlerMethod，适合做登录校验、权限判断、日志。`@ControllerAdvice` 配合 `@ExceptionHandler` 负责异常转换，`@ResponseBody` 决定是否直接写响应体。

静态资源由 `ResourceHttpRequestHandler` 处理，默认映射 `/static`、`/public`、`/resources`、`/META-INF/resources` 到根路径。自定义拦截器时要注意排除静态资源与登录接口，否则页面和验证码请求会被拦截。

## 3. 参数绑定与 JSON

常用注解：

- `@RequestParam` 绑定查询参数或表单字段，`required = false` 允许缺省，`defaultValue` 提供默认值。
- `@PathVariable` 绑定路径变量，适合 REST 风格资源 ID。
- `@RequestBody` 用 `HttpMessageConverter` 把请求体反序列化为对象，通常配合 `application/json`。
- `@RequestHeader`、`@CookieValue` 读取请求头与 Cookie。
- `@ModelAttribute` 用于表单对象绑定，`@RequestPart` 用于文件上传。

GET 请求参数是字符串，Spring 会按目标类型自动转换；日期类型需要 `@DateTimeFormat` 指定格式。JSON 请求体中的日期通常用 `@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")`。枚举绑定默认按名称匹配，忽略大小写可能因版本而异，必要时自定义 `Converter`。

Jackson 默认忽略未知字段吗？Spring Boot 默认 `FAIL_ON_UNKNOWN_PROPERTIES` 为 false，因此多余字段不会报错；如果希望严格校验可显式开启。`null` 与字段缺失在反序列化后都表现为 null，需要区分时使用 `JsonNullable` 或原始 `JsonNode`。

文件上传依赖 `MultipartFile`，Spring Boot 默认单文件上限 1MB、单请求 10MB，可通过 `spring.servlet.multipart.max-file-size` 与 `max-request-size` 调整。保存文件时不要直接使用用户提供的文件名，应生成 UUID 并校验扩展名与 Content-Type。

## 4. 参数校验

Spring Boot 2.3 以后 `spring-boot-starter-web` 不再传递引入 validation，需要显式添加 `spring-boot-starter-validation`（Hibernate Validator 实现 Jakarta Bean Validation 2.0，包名仍是 `javax.validation`）。本项目未引入该依赖时，`@Valid` 不会生效，需要在业务代码中手动校验。

常用约束注解：`@NotNull`、`@NotBlank`（字符串非 null 且去除首尾空白后非空）、`@NotEmpty`（集合/字符串非空）、`@Size`、`@Min`、`@Max`、`@Pattern`、`@Email`、`@Positive`。`@Valid` 用于级联校验对象字段，`@Validated` 在类上使用可支持分组校验，也可以在方法参数上使用以启用方法级校验。

校验失败会抛 `MethodArgumentNotValidException`（请求体）或 `BindException`（表单/查询参数），统一异常处理器可以从中提取 `FieldError`，返回字段名与提示。分组校验通过定义空接口（如 `AddGroup`、`UpdateGroup`）并在注解上指定 `groups` 实现同一 DTO 在不同场景下的不同规则。

自定义校验可以实现 `ConstraintValidator`，例如校验手机号、状态枚举、时间区间。跨字段校验通常写在类级别约束或 Service 中，因为字段级注解拿不到其他字段。

## 5. 事务管理

`@Transactional` 基于 Spring AOP 代理实现。Spring Boot 自动配置了 `DataSourceTransactionManager`，`@EnableTransactionManagement` 开启注解驱动（Spring Boot 默认已开启等价功能）。事务只对通过代理调用的 public 方法生效。

常见失效场景：

- 同类内部方法自调用：`this.method()` 不经过代理，事务不生效。可以注入自身代理、拆分到另一个 Bean，或使用 `TransactionTemplate`。
- 方法不是 public：JDK 动态代理要求接口方法为 public，CGLIB 也不能代理 private/final 方法。
- 异常被捕获没有抛出：默认只对 `RuntimeException` 与 `Error` 回滚。
- 异常类型不匹配：受检异常默认不回滚，需要 `@Transactional(rollbackFor = Exception.class)`。
- 多数据源或事务管理器不匹配。
- 类被 `final` 修饰或方法 `final`，CGLIB 无法生成子类。

传播行为常用 `REQUIRED`（默认，加入当前事务或新建）、`REQUIRES_NEW`（挂起当前事务，独立提交）、`NESTED`（保存点，外层回滚会一起回滚）、`SUPPORTS`、`NOT_SUPPORTED`。注意 `REQUIRES_NEW` 会占用新的数据库连接，嵌套过多可能耗尽连接池。

隔离级别对应数据库实现：`READ_UNCOMMITTED` 可能脏读；`READ_COMMITTED` 避免脏读，Oracle 默认；`REPEATABLE_READ` 避免不可重复读，MySQL InnoDB 默认，并通过间隙锁在很大程度上避免幻读；`SERIALIZABLE` 串行化，并发最低。Spring 的 `Isolation.DEFAULT` 表示使用数据库默认级别。

只读事务 `@Transactional(readOnly = true)` 是一个提示，MySQL 可以据此优化，但不会强制禁止写操作。长事务会持有锁并放大回滚段，应尽量缩短事务范围，把远程调用、文件上传、消息发送放到事务外。事务提交后再执行副作用可以使用 `TransactionSynchronizationManager.registerSynchronization` 的 `afterCommit`，或使用事件机制。

## 6. 全局异常处理

`@RestControllerAdvice` 是 `@ControllerAdvice` 与 `@ResponseBody` 的组合，配合 `@ExceptionHandler` 统一处理异常。可以针对不同异常返回不同 HTTP 状态码与业务错误码，也可以处理 `MethodArgumentNotValidException`、`HttpMessageNotReadableException`、`MissingServletRequestParameterException` 等框架异常。

设计要点：

- 业务异常携带错误码，响应体统一为 `{code, message, data}` 之类结构。
- 不要把所有异常都映射为 200，HTTP 状态码应语义正确，例如参数错误 400、未认证 401、无权限 403、资源不存在 404、冲突 409、服务端错误 500。
- 记录日志时区分级别：客户端错误用 warn 或 info，服务端错误用 error 并记录堆栈。
- 不要把数据库异常、堆栈、SQL 语句直接返回给前端。
- 兜底 `Exception` 处理器要放在最后，返回统一提示并生成追踪 ID 便于定位。

`@ExceptionHandler` 的匹配遵循“最具体优先”，同一异常在多个 Advice 中定义时可通过 `@Order` 控制优先级。

## 7. 拦截器、过滤器与跨域

注册拦截器需要实现 `WebMvcConfigurer` 的 `addInterceptors`，通过 `addPathPatterns` 与 `excludePathPatterns` 指定范围。拦截器可以注入 Spring Bean，适合做 JWT 校验、权限注解解析、接口耗时统计。

过滤器通过 `FilterRegistrationBean` 或 `@Component` 注册，适合做编码、请求包装、全链路追踪。过滤器与拦截器的顺序都要明确，认证失败时应直接写响应并终止链路，不要继续调用 `chain.doFilter`。

跨域请求在浏览器端受同源策略限制。开发阶段可以在 `addCorsMappings` 中配置允许的来源、方法、请求头与是否携带凭证；生产环境应精确配置来源，不要使用 `*` 与 `allowCredentials(true)` 的组合。前后端同域部署时通常不需要 CORS。

## 8. 日志与配置实践

Spring Boot 默认使用 Logback，日志级别可在 `logging.level` 下按包配置。生产环境避免输出 DEBUG，尤其是 SQL 参数、令牌、密码。日志中不要拼接用户输入的原样内容，防止日志注入。

`@ConfigurationProperties` 配置类可以配合 `@Validated` 做启动期校验；如果配置缺失应快速失败，而不是运行到一半才报错。敏感配置通过环境变量或配置中心注入，仓库中只保留占位符。

`spring.main.allow-circular-references` 打开循环依赖只是权宜之计，根本解决要靠分层与依赖倒置。构造器注入能暴露循环依赖，字段注入会掩盖问题但不利于测试。

## 9. 接口设计建议

- Controller 只做参数接收、校验、调用 Service、组装响应，不写业务逻辑。
- Service 负责事务边界与领域规则，Mapper 只负责数据访问。
- 统一响应结构与错误码，分页参数与返回结构保持一致。
- 对写接口做幂等设计，例如订单号唯一约束、去重表、状态机校验。
- 对外接口设置合理的超时与限流，避免下游故障拖垮线程池。
- 接口文档与实现同步维护，示例请求要能直接复制运行。

理解请求链路后，排查问题时就能判断故障发生在容器、过滤器、拦截器、参数绑定、Controller、Service、事务还是序列化阶段，而不是盲目加日志。

## 10. 测试与调试

单元测试优先测 Service 与工具类，使用 Mockito 隔离 Mapper 等外部依赖；集成测试再覆盖 Controller 与数据库交互。`@SpringBootTest` 会启动完整上下文，速度较慢；只测 MVC 层可以用 `@WebMvcTest` 配合 `@MockBean`。`MockMvc` 能验证状态码、响应头、JSON 路径与异常处理，异步接口需要先 `asyncStarted` 再 `asyncDispatch`。

测试数据库建议使用 H2 的 MySQL 兼容模式，或 Testcontainers 启动真实 MySQL。H2 与 MySQL 在函数、锁、索引行为上仍有差异，涉及 SQL 方言的用例不能只依赖 H2。测试之间要保证数据隔离，可以使用 `@Transactional` 回滚，或为每个用例生成独立的 source 标识。

线上排查接口问题时，先看访问日志确认请求是否到达应用，再看过滤器与拦截器日志，然后看参数绑定异常与业务日志。开启 `logging.level.org.springframework.web=DEBUG` 可以看到映射与参数解析细节，但不要在生产长期开启。Actuator 的 `/actuator/health`、`/actuator/metrics` 能快速判断应用与依赖状态，暴露端点时要做好鉴权。
