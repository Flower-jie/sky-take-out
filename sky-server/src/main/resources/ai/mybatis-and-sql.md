# MyBatis 与 SQL：映射、动态 SQL、索引与慢查询

## 1. MyBatis 核心组件

MyBatis 是半自动的持久层框架，开发者手写 SQL，框架负责参数映射、结果映射、缓存与事务整合。核心对象包括：

- `SqlSessionFactory`：全局唯一，负责创建 `SqlSession`，启动时解析所有映射文件并生成 `MappedStatement`。
- `SqlSession`：一次会话，封装数据库连接与执行器，非线程安全，用完必须关闭。
- `Executor`：执行器，有 SIMPLE、REUSE、BATCH 三种；默认 SIMPLE，BATCH 适合批量写入。
- `MappedStatement`：一条 SQL 的完整描述，包含 id、SQL 文本、参数映射、结果映射、缓存配置。
- `MapperProxy`：Mapper 接口的 JDK 动态代理，方法调用被转换成 `MappedStatement` 的 id 并交给 `SqlSession` 执行。

Mapper 接口不需要实现类，MyBatis 通过 `MapperRegistry` 注册接口与代理工厂。Spring Boot 启动时，`@MapperScan` 或 `@Mapper` 注解把接口注册为 Bean。接口全限定名与方法名拼接成 `MappedStatement` 的 id，例如 `com.sky.mapper.DishMapper.list`，因此 XML 的 `namespace` 必须与接口全限定名完全一致，否则启动时或调用时报 “Invalid bound statement (not found)”。

项目中的典型分层是 Controller → Service → Mapper → 数据库。事务通常加在 Service 层，因为一个业务操作可能涉及多个 Mapper 调用，需要保证原子性。

## 2. 参数传递与 #{} 和 ${}

`#{}` 是预编译占位符，MyBatis 会生成 `?` 并通过 `PreparedStatement.setXxx` 设置参数，能防止 SQL 注入，也能复用执行计划。`${}` 是字符串拼接，直接把值拼进 SQL，仅用于表名、列名、排序字段等无法参数化的位置，且必须对白名单校验，绝不能拼接用户输入。

单个简单参数可以直接使用任意名称，例如 `selectById(Long id)` 中 `#{id}` 或 `#{value}` 都可以。多个参数需要 `@Param` 指定名称，否则在 XML 中只能用 `#{param1}`、`#{arg0}` 这类名称，可读性差且容易出错。传入对象时直接写属性名，嵌套对象用 `#{user.name}`，集合或数组用 `foreach`。

常见类型处理器：`StringTypeHandler`、`IntegerTypeHandler`、`LocalDateTimeTypeHandler`（MyBatis 3.4.5 起内置 JSR-310 支持）。枚举默认按名称存储，也可以自定义 `TypeHandler` 存 code。`jdbcType` 在字段为 null 时可能需要显式指定，例如 Oracle 的 `jdbcType=VARCHAR`。

## 3. 结果映射

默认开启驼峰映射（`map-underscore-to-camel-case: true`）时，`create_time` 会自动映射到 `createTime`。复杂映射使用 `resultMap`：

- `id` 与 `result` 定义主键与普通列。
- `association` 映射一对一，`collection` 映射一对多。
- `javaType` 指定属性类型，`ofType` 指定集合元素类型。
- 嵌套查询用 `select` 属性会触发 N+1 查询，建议用 `join` 加 `collection` 的嵌套结果映射，或开启延迟加载并评估性能。
- `discriminator` 根据列值选择不同映射，类似继承。

`resultType` 适合列名与属性名能直接对应的场景，`resultMap` 适合列名不一致、需要嵌套映射或需要复用的情况。返回 `Map` 时列名作为 key，注意数据库大小写与别名。`selectOne` 返回多条会抛 `TooManyResultsException`，应确保条件唯一或使用 `limit 1`。

## 4. 动态 SQL

动态 SQL 是 MyBatis 的强项，常用标签：

- `<if test="...">` 条件拼接，注意 `and`、`or` 的处理。
- `<where>` 自动添加 WHERE 并去掉开头多余的 AND/OR。
- `<set>` 用于 UPDATE，自动去掉末尾多余的逗号。
- `<trim prefix suffix prefixOverrides suffixOverrides>` 自定义裁剪规则。
- `<choose><when><otherwise>` 类似 switch。
- `<foreach collection item index open close separator>` 遍历集合，常用于 IN 查询与批量插入。
- `<bind name value>` 创建变量，例如拼接 like 的模糊匹配模式。
- `<sql id>` 与 `<include refid>` 复用 SQL 片段。

模糊查询的写法：`like concat('%', #{keyword}, '%')`，或先 `bind` 再引用。不要写 `like '%${keyword}%'`，既有注入风险又无法使用索引前缀匹配（本身前置通配符也无法走普通索引）。

`foreach` 的 `collection` 在单参数集合时写 `list` 或 `collection`，使用 `@Param` 后写参数名。批量插入时注意 SQL 长度与数据库 `max_allowed_packet`，几千条以上应分批。

动态 SQL 的 `<if>` 判断字符串非空要写 `test="name != null and name != ''"`，OGNL 中 `and` 不能写成 `&&`（虽然部分版本支持，但 XML 中 `&` 需要转义）。数字与字符串比较时注意类型，`status == '1'` 在 OGNL 中可能把字符当成数字，推荐统一用整数或 `toString()`。

## 5. 主键回填、批量与缓存

插入后获取自增主键：`<insert useGeneratedKeys="true" keyProperty="id">`。Oracle 序列则用 `<selectKey order="BEFORE">`。批量插入可以写 `insert into t (...) values (...),(...),...` 配合 `foreach`，也可以使用 `ExecutorType.BATCH` 分批提交。

MyBatis 一级缓存是 `SqlSession` 级别，默认开启，同一会话中相同 SQL 与参数会命中缓存；执行增删改或调用 `clearCache` 会清空。二级缓存是 `Mapper` 级别，需要在 XML 加 `<cache/>` 或使用 `@CacheNamespace`，并要求实体可序列化；跨会话共享，容易因多表关联产生脏数据，分布式环境更应谨慎。Spring 环境下每次 Mapper 调用通常复用同一 `SqlSession`（同一事务），所以一级缓存会生效。

PageHelper 的分页原理是拦截器在 SQL 执行前改写语句：先查 count，再拼接 `limit`。它依赖 `ThreadLocal` 传递分页参数，`startPage` 后必须紧跟一次查询，否则参数会串到别的查询。物理分页比内存分页（查全部再截取）高效得多，但深分页 `limit 1000000, 20` 仍然慢，应使用“延迟关联”或基于游标（`where id > lastId order by id limit 20`）。

## 6. 索引基础

InnoDB 使用 B+ 树索引。聚簇索引的叶子节点存放整行数据，按主键组织，因此每张表只有一个聚簇索引；二级索引的叶子节点存放索引列与主键值，查询非索引列时需要“回表”再查聚簇索引。

联合索引遵循最左前缀原则：索引 `(a, b, c)` 能支持 `a`、`a,b`、`a,b,c` 的条件，但单独 `b` 或 `c` 用不上。范围查询会中断后续列的使用，例如 `a = 1 and b > 2 and c = 3` 中 `c` 无法用于定位，但可能用于索引条件下推过滤。

覆盖索引指查询的列都在索引中，无需回表，`explain` 的 Extra 显示 `Using index`。把高频查询的少量列放进联合索引可以显著减少回表。索引不是越多越好：每个索引都会增加写入成本与存储，修改数据时需要维护所有相关索引。

索引失效的常见原因：

- 对索引列使用函数或表达式，例如 `where date(create_time) = '2026-10-01'`，应改为范围查询。
- 隐式类型转换，例如字符串列与数字比较，可能把列转成数字导致索引失效。
- 前导模糊匹配 `like '%abc'`。
- `or` 连接的条件中有一侧无索引，可能导致全表扫描。
- 使用 `!=`、`not in`、`is not null` 时优化器可能放弃索引。
- 联合索引不满足最左前缀。
- 数据区分度太低（如性别），优化器可能认为全表扫描更便宜。
- 字符集或排序规则不一致导致连接字段无法使用索引。

主键建议使用自增 BIGINT，避免随机 UUID 造成页分裂。业务唯一键应建唯一索引，用数据库约束兜底并发重复。长字符串索引用前缀索引，例如 `index (name(20))`，并评估区分度。

## 7. 事务、锁与隔离级别

InnoDB 支持事务，默认隔离级别是 `REPEATABLE READ`。它通过 MVCC 提供一致性读，普通 `select` 不加锁；`select ... for update` 与 `update`、`delete` 加排他锁。行锁加在索引上，如果条件没有命中索引，可能退化为锁全表或大量行，导致并发下降。

间隙锁（Gap Lock）用于阻止其他事务在区间内插入，配合行锁组成 Next-Key Lock，可以在可重复读级别下避免大部分幻读。间隙锁之间可能互相兼容，但与插入意向锁冲突，容易在并发插入时死锁。

死锁排查：执行 `show engine innodb status` 查看 LATEST DETECTED DEADLOCK，分析两个事务的 SQL、持有锁与等待锁。降低死锁概率的方法包括：按固定顺序访问多行、缩短事务、减少范围更新、为条件列建合适索引、避免在事务中等待用户输入。

## 8. 慢查询分析与优化

开启慢查询日志：`slow_query_log = ON`、`long_query_time = 1`、`log_queries_not_using_indexes` 按需开启。使用 `mysqldumpslow` 或 `pt-query-digest` 聚合分析。

`explain` 的关键列：

- `type`：访问类型，性能从好到差大致为 `system > const > eq_ref > ref > range > index > ALL`，出现 `ALL` 且数据量大时要警惕。
- `key`：实际使用的索引；`possible_keys` 是候选索引。
- `rows`：预估扫描行数，越小越好。
- `filtered`：过滤后剩余比例。
- `Extra`：`Using index` 表示覆盖索引；`Using where` 表示回表后过滤；`Using filesort` 表示额外排序；`Using temporary` 表示使用临时表，常见于 `group by`、`distinct`、复杂子查询。

优化步骤：先用 `explain` 确认访问路径，再看是否缺少合适索引、是否能改成覆盖索引、是否能减少回表、是否能改写 SQL 让条件可索引。不要盲目加索引，也不要在 SQL 中写 `select *`，只取需要的列。

深分页优化示例：`select * from orders order by id limit 1000000, 20` 可以改为先查主键 `select id from orders order by id limit 1000000, 20`，再用 `where id in (...)` 回表；或记录上一页最后一条 id，使用 `where id > ? order by id limit 20`。后者要求排序稳定且翻页顺序固定。

## 9. 连接池与批量操作

Spring Boot 默认使用 HikariCP，关键参数包括最大连接数、最小空闲、连接超时、空闲超时、最大生命周期。最大连接数不是越大越好，应结合数据库 `max_connections`、应用实例数与查询耗时评估。连接泄漏表现为连接池耗尽，通常源于手动获取连接未关闭或事务未结束。

批量写入时，逐条 `insert` 的网络往返开销很大。可以使用 `foreach` 多值插入、`ExecutorType.BATCH` 或 JDBC `addBatch`，并合理设置批大小（如 500 到 1000）。批量更新要注意事务大小与锁范围，分批提交可以降低单次回滚成本。

## 10. 实践清单

- XML 的 namespace 与 Mapper 接口全限定名一致，方法名与 id 一致。
- 参数一律使用 `#{}`，需要拼接标识符时走白名单。
- 动态 SQL 用 `<where>`、`<set>` 处理多余关键字，避免手写 `1=1`。
- 高频查询按最左前缀设计联合索引，尽量覆盖查询列。
- 时间范围查询使用 `>=` 与 `<`，不要对列使用函数。
- 分页使用物理分页，深分页改为游标或延迟关联。
- 事务只包住必要的数据库操作，远程调用放到事务外。
- 上线前用 `explain` 检查新增 SQL，关注 `type`、`rows` 与 `Extra`。
- 监控慢查询、连接池活跃数、锁等待时间，而不是只看接口平均耗时。

## 11. 常见 SQL 写法细节

`count(*)` 会统计所有行，InnoDB 需要遍历索引，数据量大时并不快；如果只需要判断是否存在，用 `select 1 ... limit 1` 或 `exists` 更合适。`count(column)` 不统计 null，`count(1)` 与 `count(*)` 在 InnoDB 中通常等价，优化器会选择最小的二级索引扫描。

`group by` 在 MySQL 5.7 之前会隐式排序，8.0 去掉了隐式排序，但仍可能使用临时表。分组列有索引时可以避免临时表。`distinct` 在数据量大时开销明显，能用 `group by` 或应用层去重替代时要评估执行计划。

子查询要区分相关子查询与非相关子查询。相关子查询对外层每一行执行一次，容易变成 N+1；可以改写为 `join` 或先聚合再连接。`in (子查询)` 在旧版本 MySQL 中可能生成物化临时表，`exists` 在匹配到第一行就停止，选择哪种要看数据分布与执行计划，而不是凭经验下结论。

`union` 会去重并排序，`union all` 直接合并，性能更好；确定没有重复时优先 `union all`。`order by` 与 `limit` 组合时，排序列有索引且顺序一致可以避免 `filesort`，多列排序的方向要与索引列方向匹配（MySQL 8.0 支持降序索引，5.7 会对降序做反向扫描但多列混合方向仍可能排序）。

`update` 与 `delete` 一定要带 where 条件，并先用相同条件的 `select` 确认影响范围。大批量删除应分批执行，避免长事务、锁表与主从延迟。生产环境执行 DDL 前要评估元数据锁，尽量使用在线 DDL 工具。
