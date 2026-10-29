# Java 基础：集合、异常与 IO

## 1. 集合框架总览

Java 集合分为两大接口体系：`Collection` 与 `Map`。`Collection` 下有 `List`（有序、可重复）、`Set`（不重复）、`Queue`（队列）三条主线；`Map` 以键值对存储，不属于 `Collection`，但可以分别获取 key 集合、value 集合与 entry 集合。

选择集合时先看访问模式而不是名称：

- 需要按下标随机访问、以读为主：`ArrayList`。
- 需要频繁在头部插入删除、或当作栈/队列使用：`ArrayDeque` 通常优于 `LinkedList`。
- 需要去重且不关心顺序：`HashSet`；需要保留插入顺序：`LinkedHashSet`；需要排序：`TreeSet`。
- 需要键值映射且不关心顺序：`HashMap`；需要插入顺序：`LinkedHashMap`；需要按 key 排序：`TreeMap`。
- 多线程读多写少：`ConcurrentHashMap`；需要阻塞队列：`ArrayBlockingQueue`、`LinkedBlockingQueue`。

`Collection` 的 `add` 返回 boolean，表示集合是否发生变化，`Set` 重复添加会返回 false。`Map` 的 `put` 返回被覆盖的旧值，`getOrDefault` 与 `computeIfAbsent` 能减少空值判断。`Collections.unmodifiableList` 返回的是视图，原列表被修改后视图也会变化，并不是真正的不可变副本。

## 2. ArrayList 与 LinkedList

`ArrayList` 底层是 `Object[]`，默认容量为 10，但无参构造在第一次添加元素时才分配数组。扩容时新容量约为旧容量的 1.5 倍（`oldCapacity + (oldCapacity >> 1)`），并通过 `Arrays.copyOf` 复制元素。扩容有成本，能在创建时估算规模就使用 `new ArrayList<>(capacity)`。

随机访问 `get(index)` 是 O(1)。在中间插入或删除需要移动后续元素，复杂度 O(n)；尾部追加在未扩容时是 O(1)。删除元素后不会立即缩容，避免频繁抖动。

`LinkedList` 底层是双向链表，每个节点额外保存前后指针，内存开销比数组大。按下标访问需要从头或尾遍历，复杂度 O(n)。在已经拿到节点引用的位置插入删除是 O(1)，但 `add(index, e)` 仍然要先遍历定位，所以“LinkedList 插入更快”只在特定场景成立。实际开发中大多数场景用 `ArrayList` 即可，栈和队列优先用 `ArrayDeque`。

常见坑：

- `Arrays.asList(array)` 返回固定长度的列表，`add`、`remove` 会抛 `UnsupportedOperationException`。
- `List.of` 是 Java 9 的 API，Java 8 项目不能用。
- `subList` 返回的是原列表的视图，对原列表做结构性修改后视图会失效，抛 `ConcurrentModificationException`。
- 使用 for-each 遍历时删除元素会抛 `ConcurrentModificationException`，应使用 `Iterator.remove()` 或 `removeIf`。
- `ArrayList` 的 `contains` 是 O(n)，频繁判断存在性应改用 `HashSet`。

## 3. HashMap 的实现原理

JDK 8 的 `HashMap` 是“数组 + 链表 + 红黑树”。数组称为 table，长度始终是 2 的幂。插入时先对 key 的 `hashCode` 做扰动：`h ^ (h >>> 16)`，让高位参与运算，然后用 `(n - 1) & hash` 计算下标，这等价于对数组长度取模，但更快。

默认初始容量 16，负载因子 0.75，元素数量超过 `capacity * loadFactor` 时扩容为原来的 2 倍。扩容时需要把元素重新分配到新数组，JDK 8 利用容量是 2 的幂这一特点，将链表拆成低位链和高位链，避免逐个重新计算哈希。

当同一个桶中的链表长度达到 8 且数组长度至少为 64 时，链表转为红黑树，查询复杂度从 O(n) 降为 O(log n)；元素减少到 6 时退化回链表。树化阈值 8 与退化阈值 6 之间留有缓冲，避免频繁转换。

哈希冲突时，JDK 8 采用尾插法，JDK 7 采用头插法。JDK 7 在并发扩容时可能形成环形链表，导致 `get` 死循环，这也是 `HashMap` 非线程安全的一个典型表现。多线程环境应使用 `ConcurrentHashMap`，或在外层加锁。

使用 `HashMap` 的注意事项：

- key 必须正确实现 `equals` 与 `hashCode`，两者要一致：equals 相等的对象 hashCode 必须相等。
- 可变对象作为 key 后如果修改了参与哈希计算的字段，就再也查不到该条目。
- 初始容量可以按“预计元素数 / 0.75 + 1”估算，减少扩容。
- `HashMap` 允许一个 null key 和多个 null value；`Hashtable` 不允许 null；`ConcurrentHashMap` 也不允许 null key 或 null value。
- 遍历顺序与插入顺序无关，需要顺序时使用 `LinkedHashMap`。

## 4. Set、TreeMap 与比较器

`HashSet` 内部持有一个 `HashMap`，元素作为 key，value 是固定的占位对象，因此去重依赖 `hashCode` 与 `equals`。`LinkedHashSet` 在哈希表基础上维护双向链表，保留插入顺序，适合做去重且要求稳定的场景。

`TreeSet` 与 `TreeMap` 基于红黑树，按 key 排序，查找、插入、删除都是 O(log n)。使用自然排序时元素必须实现 `Comparable`；否则要传入 `Comparator`。比较器返回 0 被视为重复元素，`TreeSet` 不会加入第二个。`Comparator` 链式写法 `comparing(...).thenComparing(...)` 在 Java 8 可用，注意避免在比较器中做昂贵计算。

## 5. equals 与 hashCode 契约

重写 `equals` 时必须同时重写 `hashCode`，否则在 `HashMap`、`HashSet` 中会出现“两个相等对象被当成不同 key”的问题。契约包括：自反性、对称性、传递性、一致性，以及与 null 比较返回 false。

推荐使用 IDE 生成或 `Objects.equals`、`Objects.hash`。注意数组的 `equals` 比较的是引用，应使用 `Arrays.equals`。浮点数比较不要直接用 `==`，可使用 `Double.compare` 或误差范围。

## 6. 异常体系

`Throwable` 分为 `Error` 与 `Exception`。`Error` 表示 JVM 层面的严重问题，如 `OutOfMemoryError`、`StackOverflowError`，通常不应捕获。`Exception` 分为受检异常（checked）与非受检异常（unchecked，继承 `RuntimeException`）。

受检异常必须在方法签名声明或捕获，例如 `IOException`、`SQLException`；非受检异常如 `NullPointerException`、`IllegalArgumentException`、`IndexOutOfBoundsException` 不强制处理。业务代码中常见的做法是把可恢复的失败包装成自定义运行时异常，交给全局异常处理器统一转换。

`finally` 几乎总会执行，除非 JVM 退出（`System.exit`）或线程被强制终止。若 `try` 与 `finally` 都有 return，`finally` 的返回值会覆盖 `try` 的返回值，因此不要在 `finally` 中写 return。`try-with-resources` 会自动关闭实现了 `AutoCloseable` 的资源，关闭顺序与声明顺序相反，且不会吞掉原始异常，是 Java 7 以后推荐写法。

异常处理原则：

- 捕获具体异常，不要一律 `catch (Exception e)` 后什么都不做。
- 不要用异常控制正常流程，异常对象的构造与栈追踪有性能成本。
- 包装异常时保留 cause，使用 `new RuntimeException("...", e)`，方便定位根因。
- 日志中记录异常对象本身，而不是只记录 `e.getMessage()`。
- 对外接口不要直接暴露堆栈信息，应返回统一的错误码与提示。

## 7. IO 基础

Java IO 分为字节流与字符流。字节流以 `InputStream`、`OutputStream` 为基类，适合图片、压缩包等二进制数据；字符流以 `Reader`、`Writer` 为基类，适合文本，内部依赖字符编码。

常见组合：

- 文件读取：`new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))`。
- 文件写入：`new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))`。
- 便捷读写：Java 7 的 `Files.readAllBytes`、`Files.newBufferedReader`、`Files.write`。

`FileInputStream.read()` 返回的是 0 到 255 的 int，读到末尾返回 -1；如果误把返回值强转成 byte 再判断 -1，会出错。字符流默认使用平台编码，Windows 中文环境可能是 GBK，跨平台时务必显式指定 UTF-8。

`BufferedInputStream`、`BufferedReader` 通过缓冲区减少系统调用，通常要包在底层流外面。关闭外层流会级联关闭内层流，但多个流分别打开时要注意关闭顺序。`flush` 只保证缓冲区写入底层，不保证落盘；需要落盘可调用 `FileDescriptor.sync()`。

NIO 的 `ByteBuffer`、`Channel`、`Selector` 适合高并发网络编程，但文件读写场景下 `Files` 工具类已经足够。`MappedByteBuffer` 可以做内存映射，但释放时机由 GC 控制，删除映射文件在 Windows 上可能失败。

## 8. String 与字符串拼接

`String` 是不可变类，内部字符数组被 final 修饰，所有“修改”都会产生新对象。字符串字面量存放在常量池，`intern()` 可以把运行时字符串放入常量池并返回引用。

循环中拼接字符串不要直接用 `+`，编译器虽然会把单条语句的拼接优化成 `StringBuilder`，但循环体内的 `+` 每次都会创建新的 `StringBuilder`，复杂度退化为 O(n²)。应显式使用 `StringBuilder`，线程安全场景才考虑 `StringBuffer`。

`String.equals` 比较内容，`==` 比较引用。判断字符串是否为空使用 `isEmpty()` 或 `length() == 0`；Java 8 没有 `isBlank()`，需要自己用 `trim().isEmpty()` 判断空白。`split` 的参数是正则表达式，按 `.` 或 `|` 分割时要转义。

## 9. 泛型、包装类与自动装箱

泛型在编译期做类型检查，运行时被擦除，因此不能 `new T()`、不能创建泛型数组，静态成员也不能使用类的类型参数。通配符 `? extends T` 适合读取，`? super T` 适合写入，即 PECS 原则。

包装类 `Integer` 对 -128 到 127 的数值有缓存，`Integer.valueOf(127) == Integer.valueOf(127)` 为 true，而 128 为 false。包装类与基本类型比较会自动拆箱，但两个包装类用 `==` 比较的是引用，应使用 `equals` 或先转成基本类型。`null` 的包装类参与运算会抛 `NullPointerException`。

## 10. Java 8 常用特性

Lambda 表达式依赖函数式接口（只有一个抽象方法的接口），如 `Runnable`、`Comparator`、`Function`、`Supplier`。方法引用 `String::length` 是 lambda 的简写。

Stream 是惰性的：`filter`、`map` 只是构建流水线，遇到终止操作 `collect`、`forEach`、`count` 才执行。`anyMatch`、`findFirst` 是短路操作。流只能消费一次，重复使用会抛 `IllegalStateException`。并行流默认使用公共 ForkJoinPool，IO 密集或数据量小时不建议使用。

`Optional` 用于表达“可能没有值”的返回值，不建议作为字段或方法参数，也不要用 `Optional.get()` 而不判断。`orElse` 无论是否为空都会计算参数，`orElseGet` 只在为空时执行 Supplier。

接口的默认方法用于在不破坏实现类的前提下扩展接口，静态方法可以放工具函数。Java 8 的时间 API `LocalDate`、`LocalDateTime`、`Duration` 是不可变且线程安全的，替代了大部分 `Date` 与 `SimpleDateFormat` 的使用场景。

## 11. 常见问题排查

`OutOfMemoryError: Java heap space` 通常是对象无法回收或一次性加载数据过多；`Metaspace` 溢出多与动态生成类有关；`StackOverflowError` 多为递归过深。排查时可以用 `jps` 找进程，`jstack` 看线程栈，`jmap` 或 `jcmd` 导出堆转储，再配合 MAT 分析。

序列化要求类实现 `Serializable`，并显式声明 `serialVersionUID`，否则类结构变化后反序列化可能失败。`transient` 字段不参与默认序列化。生产环境更推荐 JSON 等跨语言格式。

## 12. 数组、排序与工具类

数组长度固定，创建后不能扩容，`length` 是属性而不是方法。`Arrays.toString` 用于打印一维数组，多维数组要用 `Arrays.deepToString`。`Arrays.sort` 对基本类型使用双轴快排，对对象数组使用 TimSort（稳定排序，最坏 O(n log n)）。`Arrays.binarySearch` 要求数组已排序，否则结果不可预测。

`Collections.sort` 与 `List.sort` 都要求比较器满足自反、反对称与传递性，否则可能抛 `IllegalArgumentException: Comparison method violates its general contract!`。比较整数时不要用减法强转 int，可能溢出，应使用 `Integer.compare`。

`Objects.requireNonNull` 可以在入口快速失败，`Objects.equals` 能安全处理 null，`Objects.hash` 方便生成哈希值。`Math.addExact` 等精确运算方法在溢出时抛异常，适合金额与计数场景。金额计算不要使用 `double`，应使用 `BigDecimal`，并通过 `setScale` 明确精度与舍入模式。
