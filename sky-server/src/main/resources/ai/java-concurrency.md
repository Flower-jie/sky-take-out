# Java 并发：线程、锁、线程池与 ThreadLocal

## 1. 线程基础

创建线程有四种常见方式：继承 `Thread`、实现 `Runnable`、实现 `Callable` 配合 `FutureTask`、使用线程池。生产代码几乎总是使用线程池，直接 `new Thread()` 无法复用线程，也不好统一管理生命周期与异常。

`start()` 会启动新线程并执行 `run()`；直接调用 `run()` 只是在当前线程同步执行。每个线程只能 `start` 一次，重复调用会抛 `IllegalStateException`。

线程状态包括 NEW、RUNNABLE、BLOCKED、WAITING、TIMED_WAITING、TERMINATED。注意 Java 把操作系统层面的就绪、运行、IO 等待都归入 RUNNABLE，因此看到 RUNNABLE 并不代表一定在占用 CPU。

`Thread.sleep` 不释放锁；`Object.wait` 会释放监视器锁并进入等待队列，必须在 `synchronized` 块内调用。`notify` 随机唤醒一个等待线程，`notifyAll` 唤醒全部，通常更安全。等待条件必须放在 `while` 循环里，防止虚假唤醒。

中断是协作式的：`interrupt()` 只是设置中断标志，不会强制杀死线程。阻塞方法（sleep、wait、join、可中断 IO）会抛出 `InterruptedException` 并清除标志。捕获后要么重新设置 `Thread.currentThread().interrupt()`，要么向上抛出，不要静默吞掉。CPU 密集型循环可以通过 `Thread.interrupted()` 主动检查并退出。

## 2. synchronized 与监视器

`synchronized` 可以修饰实例方法、静态方法和代码块。实例方法锁的是 `this`，静态方法锁的是 `Class` 对象，代码块锁的是括号里的对象引用。锁的粒度应尽量小，但要注意把需要原子性的操作全部包含在同一临界区内。

JVM 使用对象头中的 Mark Word 记录锁状态，JDK 6 以后有偏向锁、轻量级锁、重量级锁的升级过程。偏向锁在无竞争时避免 CAS；出现竞争升级为轻量级锁，通过 CAS 自旋尝试获取；自旋失败或竞争激烈时膨胀为重量级锁，线程进入操作系统阻塞。锁升级不可逆。JDK 15 以后偏向锁被废弃，JDK 8 项目中仍然存在。

`synchronized` 是可重入的，同一线程可以多次获取同一把锁，释放次数与获取次数对应。它保证原子性、可见性和有序性：释放锁前的写操作对随后获得同一把锁的线程可见。

常见错误：

- 锁对象在每个线程中重新创建，例如 `synchronized (new Object())`，等于没锁。
- 锁 String 常量或包装类缓存对象，可能与其他代码意外共享同一把锁。
- 锁的字段被重新赋值，导致不同线程锁住不同对象。
- 持锁执行远程调用或 IO，导致锁持有时间过长。

## 3. volatile 与 Java 内存模型

Java 内存模型（JMM）规定每个线程有工作内存，共享变量在主内存。没有同步时，一个线程的写不保证另一个线程立即可见。

`volatile` 保证可见性与有序性：写 volatile 变量会立即刷新到主内存，读会从主内存读取；同时禁止特定指令重排。它不保证复合操作的原子性，`count++` 是“读-改-写”三步，即使变量是 volatile 也会丢失更新，应使用 `AtomicInteger` 或加锁。

双重检查锁单例必须给实例字段加 `volatile`，否则可能拿到“构造未完成”的对象，因为分配内存、初始化、赋值引用可能被重排。

happens-before 是判断可见性的核心规则，包括：程序顺序规则、监视器锁规则（解锁先于加锁）、volatile 规则（写先于读）、线程启动规则（`start` 先于线程内操作）、线程终止规则（线程内操作先于 `join` 返回）、传递性。理解这些规则比死记术语更有用。

## 4. ThreadLocal

`ThreadLocal` 为每个线程提供独立副本，常用于保存用户上下文、请求追踪 ID、数据库连接或 `SimpleDateFormat`。`ThreadLocal` 本身不存值，值存放在 `Thread` 对象的 `ThreadLocalMap` 中，key 是 `ThreadLocal` 的弱引用，value 是强引用。

因为 key 是弱引用，当外部不再持有 `ThreadLocal` 引用时，key 会被 GC 回收，但 value 仍被 Entry 强引用，形成“key 为 null 的 Entry”。如果线程长期存活（线程池），这些 value 无法释放，就会造成内存泄漏。因此使用完必须调用 `remove()`，通常放在 `finally` 中。

`InheritableThreadLocal` 允许子线程继承父线程的值，但只在线程创建时复制一次，线程池场景下复用线程不会重新继承，因此不适合在池化环境中传递请求上下文。Spring 的 `RequestContextHolder`、事务同步、`SecurityContext` 都使用 `ThreadLocal`，异步执行或切换线程时会丢失上下文，需要显式传递。

## 5. 线程池 ThreadPoolExecutor

`ThreadPoolExecutor` 有七个核心参数：核心线程数 `corePoolSize`、最大线程数 `maximumPoolSize`、空闲存活时间 `keepAliveTime`、时间单位、工作队列 `workQueue`、线程工厂 `threadFactory`、拒绝策略 `handler`。

任务提交后的处理顺序：

1. 当前线程数小于核心线程数，创建核心线程执行任务。
2. 核心线程已满，任务进入工作队列排队。
3. 队列已满且线程数小于最大线程数，创建非核心线程。
4. 队列已满且线程数达到最大，执行拒绝策略。

这个顺序说明：使用无界队列（如 `LinkedBlockingQueue` 默认容量 `Integer.MAX_VALUE`）时，`maximumPoolSize` 永远不会生效，任务会无限堆积，最终 OOM。因此生产环境应使用有界队列并明确拒绝策略。

四种拒绝策略：`AbortPolicy` 抛 `RejectedExecutionException`（默认）；`CallerRunsPolicy` 由提交任务的线程执行，形成背压；`DiscardPolicy` 静默丢弃；`DiscardOldestPolicy` 丢弃队列中最老的任务。业务上通常需要自定义策略，记录日志并返回明确的失败结果。

`Executors` 提供的快捷工厂有风险：`newFixedThreadPool` 与 `newSingleThreadExecutor` 使用无界队列；`newCachedThreadPool` 最大线程数为 `Integer.MAX_VALUE`，高并发下可能创建大量线程；`newScheduledThreadPool` 的队列也无界。因此《阿里巴巴 Java 开发手册》要求手动 `new ThreadPoolExecutor`。

线程数估算：CPU 密集型约为核数 + 1；IO 密集型可以更大，常见公式是 `核数 * (1 + 平均等待时间 / 平均计算时间)`，最终要压测验证。核心线程是否允许超时可通过 `allowCoreThreadTimeOut(true)` 设置。

线程池应优雅关闭：`shutdown()` 不再接受新任务，等待已提交任务完成；`shutdownNow()` 尝试中断正在执行的任务并返回未执行队列。关闭后应 `awaitTermination` 等待一段时间。Spring 中可以用 `ThreadPoolTaskExecutor` 并配置 `setWaitForTasksToCompleteOnShutdown`。

线程工厂应给线程起有意义的名字，便于 `jstack` 排查。未捕获异常可以通过 `setUncaughtExceptionHandler` 或重写 `afterExecute` 记录。

## 6. 队列与阻塞

`ArrayBlockingQueue` 基于数组，有界，支持公平/非公平锁；`LinkedBlockingQueue` 基于链表，默认无界但可指定容量；`SynchronousQueue` 不存储元素，每个插入必须等待对应取出，适合直接交接；`PriorityBlockingQueue` 是无界优先队列；`DelayQueue` 用于延时任务；`LinkedTransferQueue` 适合高吞吐生产者消费者。

队列的 `put`/`take` 会阻塞，`offer`/`poll` 可以带超时，`add`/`remove` 在失败时抛异常。选择时要区分“等待”与“立即失败”的语义。

## 7. CAS、AQS 与并发容器

CAS（Compare-And-Swap）由 CPU 指令保证原子性，包含内存位置、期望值、新值三个操作数。Java 通过 `Unsafe` 暴露 CAS，`AtomicInteger`、`AtomicReference`、`LongAdder` 都基于此。CAS 的 ABA 问题可以用 `AtomicStampedReference` 增加版本号解决。高竞争下 CAS 自旋会消耗 CPU，`LongAdder` 通过分段累加降低热点。

AQS（AbstractQueuedSynchronizer）用一个 volatile 的 state 表示同步状态，配合 CLH 双向队列管理等待线程。`ReentrantLock`、`Semaphore`、`CountDownLatch`、`ReentrantReadWriteLock` 都基于 AQS。`ReentrantLock` 支持公平锁、可中断获取、超时获取和多个条件队列，比 `synchronized` 更灵活，但必须在 `finally` 中 `unlock()`。

`ConcurrentHashMap` 在 JDK 8 中使用 CAS + synchronized 锁单个桶，放弃 JDK 7 的分段锁，读操作基本无锁。它不允许 null key/value，复合操作应使用 `putIfAbsent`、`computeIfAbsent`、`merge` 保证原子性。`size()` 在高并发下是估算值。

`CopyOnWriteArrayList` 写时复制，适合读多写极少且数据量小的场景，写操作开销大且迭代器是快照。`Collections.synchronizedList` 对所有方法加同一把锁，迭代时仍需手动同步。

## 8. 死锁与排查

死锁需要同时满足四个条件：互斥、请求并保持、不可剥夺、循环等待。破坏任一条件即可避免，工程上最常用的是固定加锁顺序、使用带超时的 `tryLock`、缩小锁范围、避免嵌套锁。

排查步骤：用 `jps -l` 找到进程，`jstack <pid>` 输出线程栈，搜索 “Found one Java-level deadlock”，查看互相等待的锁与线程。也可以使用 `jconsole`、`VisualVM` 或 Arthas 的 `thread` 命令。预防比排查更重要：不要在持锁时调用外部服务，不要在不同方法中以不同顺序获取多把锁。

## 9. 并发工具与异步

`CountDownLatch` 是一次性倒计时门闩，主线程 `await`，工作线程 `countDown`；`CyclicBarrier` 可重复使用，一组线程互相等待到齐后继续；`Semaphore` 控制同时访问的许可数量，适合限流。

`CompletableFuture` 支持链式异步编排：`supplyAsync`、`thenApply`、`thenCompose`、`thenCombine`、`exceptionally`、`handle`。默认使用 `ForkJoinPool.commonPool()`，IO 任务应传入自定义线程池，避免占满公共池。`join()` 抛非受检异常，`get()` 抛受检异常，两者都会阻塞。

`Future.get()` 阻塞且没有回调，多个任务串行 get 会失去并行意义；应使用 `CompletableFuture.allOf` 或 `invokeAll` 统一等待。取消任务只是设置取消标志，任务本身需要响应中断才会真正停止。

## 10. 实践建议

- 共享可变状态优先考虑不可变对象、线程封闭或无共享设计。
- 能用并发容器就不用自己加锁，能用 `Atomic` 就不用 `synchronized` 包住简单计数。
- 锁的顺序、粒度、持有时间要统一评审，避免在锁内做 IO。
- 线程池参数要结合队列容量、拒绝策略、监控指标一起设计。
- 任何 `ThreadLocal` 使用点都要配对 `remove()`，异步场景显式传递上下文。
- 压测时关注 CPU、上下文切换、GC、队列积压和拒绝次数，而不是只看平均响应时间。

## 11. 读写锁与锁优化

`ReentrantReadWriteLock` 允许多个读线程同时持有读锁，读写、写写互斥。适合读远多于写、且读操作耗时较长的场景；如果读操作很短，读写锁的额外开销可能不如直接用 `synchronized`。它支持锁降级：先获取写锁，再获取读锁，然后释放写锁，这样在释放写锁后仍能保持读一致性。读锁不能直接升级为写锁，否则会死锁。

`StampedLock` 提供乐观读，读之前获取 stamp，读完再校验 stamp 是否变化，变化才退化为悲观读锁。它不可重入，也不支持条件变量，使用时必须严格配对。

JVM 会做锁消除（逃逸分析证明锁对象不会被共享）和锁粗化（把相邻的多次加锁合并），这些优化对业务代码透明。伪共享是指多个线程修改同一缓存行中的不同变量导致缓存行反复失效，高频计数器可以用填充字段或 `@Contended` 缓解。

## 12. 生产者消费者示例

使用阻塞队列实现生产者消费者最简单，队列本身负责阻塞与唤醒：

```java
BlockingQueue<Order> queue = new ArrayBlockingQueue<>(200);
Runnable producer = () -> {
    try {
        queue.put(new Order());
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
};
Runnable consumer = () -> {
    try {
        Order order = queue.take();
        handle(order);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
};
```

关键是正确处理 `InterruptedException`：捕获后恢复中断标志并退出，不要继续循环。若要支持停止，可使用 poison pill（毒丸对象）或 `volatile` 停止标志配合超时 `poll`。
