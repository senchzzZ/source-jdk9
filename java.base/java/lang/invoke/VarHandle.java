/*
 * Copyright (c) 2014, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang.invoke;

import jdk.internal.HotSpotIntrinsicCandidate;
import jdk.internal.util.Preconditions;
import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.Stable;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import static java.lang.invoke.MethodHandleStatics.UNSAFE;
import static java.lang.invoke.MethodHandleStatics.newInternalError;

/**
 * A VarHandle is a dynamically strongly typed reference to a variable, or to a
 * parametrically-defined family of variables, including static fields,
 * non-static fields, array elements, or components of an off-heap data
 * structure.  Access to such variables is supported under various
 * <em>access modes</em>, including plain read/write access, volatile
 * read/write access, and compare-and-swap.
 * VarHandle是变量或明确参数类型变量（包括静态字段、非静态字段、数组元素和堆外数据结构变量）的强类型引用。
 * 支持在不同访问模型下对这些类型变量的访问，包括简单的read/write访问，volatile类型的read/write访问，和CAS(compare-and-swap)。
 *
 * <p>VarHandles are immutable and have no visible state.  VarHandles cannot be
 * subclassed by the user.
 * VarHandle是不可变的，并且没有可见的状态，并且不能被继承。
 *
 * <p>A VarHandle has:
 * <ul>
 * <li>a {@link #varType variable type} T, the type of every variable referenced
 * by this VarHandle; and
 * <li>a list of {@link #coordinateTypes coordinate types}
 * {@code CT1, CT2, ..., CTn}, the types of <em>coordinate expressions</em> that
 * jointly locate a variable referenced by this VarHandle.
 * </ul>
 * Variable and coordinate types may be primitive or reference, and are
 * represented by {@code Class} objects.  The list of coordinate types may be
 * empty.
 * “变量类型”和“坐标类型”可以是原始类型或引用类型，统一使用Class对象来代表，坐标类型可以为空。
 *
 * <p>Factory methods that produce or {@link java.lang.invoke.MethodHandles.Lookup
 * lookup} VarHandle instances document the supported variable type and the list
 * of coordinate types.
 * VarHandle使用MethodHandles的工厂方法或Lookup来创建
 *
 * <p>Each access mode is associated with one <em>access mode method</em>, a
 * <a href="MethodHandle.html#sigpoly">signature polymorphic</a> method named
 * for the access mode.  When an access mode method is invoked on a VarHandle
 * instance, the initial arguments to the invocation are coordinate expressions
 * that indicate in precisely which object the variable is to be accessed.
 * Trailing arguments to the invocation represent values of importance to the
 * access mode.  For example, the various compare-and-set or compare-and-exchange
 * access modes require two trailing arguments for the variable's expected value
 * and new value.
 * 每一个访问模式都与一个相应的方法(access mode method，后面称“访问模式方法”)与之关联（access mode method：为当前访问模式命名的多态签名方法）。
 * 当一个“access mode method”被调用，调用的初始参数是协调表达式，该表达式精确地指示了要访问变量的对象，
 * 后续的调用参数表示当前访问模式的值。例如，CAS方法需要两个后续参数：预期值和新值。
 *
 * <p>The arity and types of arguments to the invocation of an access mode
 * method are not checked statically.  Instead, each access mode method
 * specifies an {@link #accessModeType(AccessMode) access mode type},
 * represented as an instance of {@link MethodType}, that serves as a kind of
 * method signature against which the arguments are checked dynamically.  An
 * access mode type gives formal parameter types in terms of the coordinate
 * types of a VarHandle instance and the types for values of importance to the
 * access mode.  An access mode type also gives a return type, often in terms of
 * the variable type of a VarHandle instance.  When an access mode method is
 * invoked on a VarHandle instance, the symbolic type descriptor at the
 * call site, the run time types of arguments to the invocation, and the run
 * time type of the return value, must <a href="#invoke">match</a> the types
 * given in the access mode type.  A runtime exception will be thrown if the
 * match fails.
 * “访问模式方法”的参数数量和类型不会静态地检查。
 * 每一个“访问模式方法”都会通过accessModeType(AccessMode)方法指定一个“访问模式类型”，
 * accessModeType方法返回一个MethodType实例，作为一个方法签名动态地检查参数。
 * 一个“访问模式类型”根据VarHandle实例的“坐标类型”和值类型来提供正式的参数类型；
 * 一个“访问模式类型”也会根据VarHandle实例的变量类型来提供返回类型。
 * 当一个“访问模式方法”在VarHandle实例中被调用时，在调用站点上的符号引用类型、运行时参数类型，
 * 和运行时返回值类型，必须与“访问模式类型”匹配，如果匹配失败将会抛出运行时异常。
 *
 * 例如，compareAndSet方法，如果它的接收者是一个VarHandle实例，“坐标类型”为{CT1, ..., CTn}，
 * 变量类型为T，那么它的“访问模式类型”就是 (CT1 c1, ..., CTn cn, T expectedValue, T newValue)。
 *
 * 如果访问的是一个数组，这个数组的“坐标类型”为String[]和 int，变量类型为String，
 * 对于compareAndSet方法，它的“访问模式类型”就是(String[] c1, int c2, String expectedValue, String newValue)
 *
 * For example, the access mode method {@link #compareAndSet} specifies that if
 * its receiver is a VarHandle instance with coordinate types
 * {@code CT1, ..., CTn} and variable type {@code T}, then its access mode type
 * is {@code (CT1 c1, ..., CTn cn, T expectedValue, T newValue)boolean}.
 * Suppose that a VarHandle instance can access array elements, and that its
 * coordinate types are {@code String[]} and {@code int} while its variable type
 * is {@code String}.  The access mode type for {@code compareAndSet} on this
 * VarHandle instance would be
 * {@code (String[] c1, int c2, String expectedValue, String newValue)boolean}.
 * Such a VarHandle instance may produced by the
 * {@link MethodHandles#arrayElementVarHandle(Class) array factory method} and
 * access array elements as follows:
 * <pre> {@code
 * String[] sa = ...
 * VarHandle avh = MethodHandles.arrayElementVarHandle(String[].class);
 * boolean r = avh.compareAndSet(sa, 10, "expected", "new");
 * }</pre>
 *
 *
 * <p>Access modes control atomicity and consistency properties.
 * <em>Plain</em> read ({@code get}) and write ({@code set})
 * accesses are guaranteed to be bitwise atomic only for references
 * and for primitive values of at most 32 bits, and impose no observable
 * ordering constraints with respect to threads other than the
 * executing thread. <em>Opaque</em> operations are bitwise atomic and
 * coherently ordered with respect to accesses to the same variable.
 * In addition to obeying Opaque properties, <em>Acquire</em> mode
 * reads and their subsequent accesses are ordered after matching
 * <em>Release</em> mode writes and their previous accesses.  In
 * addition to obeying Acquire and Release properties, all
 * <em>Volatile</em> operations are totally ordered with respect to
 * each other.
 * 访问模式控制着原子性和一致性属性。
 * 对于引用类型和32位以内的原始类型，简单的读和写(get、set)都可以保证原子性，并对执行线程以外的线程不可见。
 * 不透明操作按照原子顺序排列，并且对相同变量的访问是有序的。
 * 除了遵循不透明属性以外，写入模式在Release前必须要在读取模式Acquire后，
 * 所有Volatile变量的操作相互之间都是有序的。
 *
 * <p>Access modes are grouped into the following categories:
 * 访问模式和访问方法：
 * <ul>
 * <li>read access modes that get the value of a variable under specified
 * memory ordering effects.
 * The set of corresponding access mode methods belonging to this group
 * consists of the methods
 *
 * read访问模式，在指定的内存排序作用下获取一个变量的值：
 * {@link #get get},
 * {@link #getVolatile getVolatile},
 * {@link #getAcquire getAcquire},
 * {@link #getOpaque getOpaque}.
 * <li>write access modes that set the value of a variable under specified
 * memory ordering effects.
 * The set of corresponding access mode methods belonging to this group
 * consists of the methods
 *
 * write访问模式，在指定内存排序作用下设置一个变量的值：
 * {@link #set set},
 * {@link #setVolatile setVolatile},
 * {@link #setRelease setRelease},
 * {@link #setOpaque setOpaque}.
 * <li>atomic update access modes that, for example, atomically compare and set
 * the value of a variable under specified memory ordering effects.
 * The set of corresponding access mode methods belonging to this group
 * consists of the methods
 *
 * 原子性地更新访问模式，例如，在指定内存排序作用下原子性地比较并替换变量的值：
 * {@link #compareAndSet compareAndSet},
 * {@link #weakCompareAndSetPlain weakCompareAndSetPlain},
 * {@link #weakCompareAndSet weakCompareAndSet},
 * {@link #weakCompareAndSetAcquire weakCompareAndSetAcquire},
 * {@link #weakCompareAndSetRelease weakCompareAndSetRelease},
 * {@link #compareAndExchangeAcquire compareAndExchangeAcquire},
 * {@link #compareAndExchange compareAndExchange},
 * {@link #compareAndExchangeRelease compareAndExchangeRelease},
 * {@link #getAndSet getAndSet},
 * {@link #getAndSetAcquire getAndSetAcquire},
 * {@link #getAndSetRelease getAndSetRelease}.
 * <li>numeric atomic update access modes that, for example, atomically get and
 * set with addition the value of a variable under specified memory ordering
 * effects.
 * The set of corresponding access mode methods belonging to this group
 * consists of the methods
 *
 * 数字类型原子访问模式，例如，在指定内存排序作用下原子性地获取并设置变量的值：
 * {@link #getAndAdd getAndAdd},
 * {@link #getAndAddAcquire getAndAddAcquire},
 * {@link #getAndAddRelease getAndAddRelease},
 * <li>bitwise atomic update access modes that, for example, atomically get and
 * bitwise OR the value of a variable under specified memory ordering
 * effects.
 * The set of corresponding access mode methods belonging to this group
 * consists of the methods
 *
 * 按位原子性更新访问，例如，在执行内存排序作用下原子性地获取并按位运算变量的值：
 * {@link #getAndBitwiseOr getAndBitwiseOr},
 * {@link #getAndBitwiseOrAcquire getAndBitwiseOrAcquire},
 * {@link #getAndBitwiseOrRelease getAndBitwiseOrRelease},
 * {@link #getAndBitwiseAnd getAndBitwiseAnd},
 * {@link #getAndBitwiseAndAcquire getAndBitwiseAndAcquire},
 * {@link #getAndBitwiseAndRelease getAndBitwiseAndRelease},
 * {@link #getAndBitwiseXor getAndBitwiseXor},
 * {@link #getAndBitwiseXorAcquire getAndBitwiseXorAcquire},
 * {@link #getAndBitwiseXorRelease getAndBitwiseXorRelease}.
 * </ul>
 *
 * <p>Factory methods that produce or {@link java.lang.invoke.MethodHandles.Lookup
 * lookup} VarHandle instances document the set of access modes that are
 * supported, which may also include documenting restrictions based on the
 * variable type and whether a variable is read-only.  If an access mode is not
 * supported then the corresponding access mode method will on invocation throw
 * an {@code UnsupportedOperationException}.  Factory methods should document
 * any additional undeclared exceptions that may be thrown by access mode
 * methods.
 * 生成VarHandle实例的工厂方法或lookup方法记录了方法支持的访问模式，包括基于变量类型和变量只读类型的限制。
 * 如果一个访问模式不被支持，调用对应的“访问模式方法”将会抛出UnsupportedOperationException。
 *
 * The {@link #get get} access mode is supported for all
 * VarHandle instances and the corresponding method never throws
 * {@code UnsupportedOperationException}.
 * If a VarHandle references a read-only variable (for example a {@code final}
 * field) then write, atomic update, numeric atomic update, and bitwise atomic
 * update access modes are not supported and corresponding methods throw
 * {@code UnsupportedOperationException}.
 * Read/write access modes (if supported), with the exception of
 * {@code get} and {@code set}, provide atomic access for
 * reference types and all primitive types.
 * Unless stated otherwise in the documentation of a factory method, the access
 * modes {@code get} and {@code set} (if supported) provide atomic access for
 * reference types and all primitives types, with the exception of {@code long}
 * and {@code double} on 32-bit platforms.
 * get访问模式对所有VarHandle实例都提供支持，并且对应的方法永远不会抛出UnsupportedOperationException异常。
 * 如果一个VarHandle引用了一个只读的变量（例如final变量），
 * 那么对其的写、原子更新、数字原子更新和按位原子更新访问模式都不会支持，并且对应的方法会抛出UnsupportedOperationException异常。
 * read/write访问模式（如果支持）除了get和set方法之外，其他方法对引用类型和所有的原始类型变量都提供原子访问。
 * 除非在工厂方法中另外有说明，get和set访问模式对引用类型和原始类型（除了32位平台的long和double）都提供原子性访问。
 *
 * <p>Access modes will override any memory ordering effects specified at
 * the declaration site of a variable.  For example, a VarHandle accessing a
 * a field using the {@code get} access mode will access the field as
 * specified <em>by its access mode</em> even if that field is declared
 * {@code volatile}.  When mixed access is performed extreme care should be
 * taken since the Java Memory Model may permit surprising results.
 * 访问模式将覆盖在变量声明时指定的任何内存排序效果
 * 例如，一个VarHandle使用get模式访问一个字段时，即使这个字段已经被声明为volatile，
 * 也会把这个字段当做它指定的访问模式进行访问。当执行混合访问时应该非常小心，因为Java内存模型可能会带来令人惊讶的结果。
 *
 * <p>In addition to supporting access to variables under various access modes,
 * a set of static methods, referred to as memory fence methods, is also
 * provided for fine-grained control of memory ordering.
 * 除了支持在各种访问模式下访问变量之外，还提供了一组内存屏障方法，为内存排序提供细粒度的控制。
 *
 * The Java Language Specification permits other threads to observe operations
 * as if they were executed in orders different than are apparent in program
 * source code, subject to constraints arising, for example, from the use of
 * locks, {@code volatile} fields or VarHandles.  The static methods,
 * {@link #fullFence fullFence}, {@link #acquireFence acquireFence},
 * {@link #releaseFence releaseFence}, {@link #loadLoadFence loadLoadFence} and
 * {@link #storeStoreFence storeStoreFence}, can also be used to impose
 * constraints.  Their specifications, as is the case for certain access modes,
 * are phrased in terms of the lack of "reorderings" -- observable ordering
 * effects that might otherwise occur if the fence was not present.  More
 * precise phrasing of the specification of access mode methods and memory fence
 * methods may accompany future updates of the Java Language Specification.
 * Java语言规范允许其他线程查看当前线程的操作，但是看到的执行顺序会不同于程序源码中显而易见的顺序。
 * 例如，从锁的使用来看，volatile字段或VarHandle。
 * 静态方法fullFence、acquireFence、releaseFence、loadLoadFence、storeStoreFence，同样可以用来强加这些约束。
 * 所有这些特性可以概括为缺乏“内存重排序”，如果这些内存栅栏不存在，则可能会发生可观察的内存排序。
 * 访问模式方法和内存栅栏方法的规范将会伴随着Java语言规范更新
 *
 * <h1>Compiling invocation of access mode methods</h1>
 * 访问模式方法是如何编译的
 * A Java method call expression naming an access mode method can invoke a
 * VarHandle from Java source code.  From the viewpoint of source code, these
 * methods can take any arguments and their polymorphic result (if expressed)
 * can be cast to any return type.  Formally this is accomplished by giving the
 * access mode methods variable arity {@code Object} arguments and
 * {@code Object} return types (if the return type is polymorphic), but they
 * have an additional quality called <em>signature polymorphism</em> which
 * connects this freedom of invocation directly to the JVM execution stack.
 * 通过方法名可以从java源码中调用VarHandle的访问模式方法。
 * 从方法源码的角度来看，这些方法可以接收任何参数，并且它们的返回值可以转换为任何类型。
 * 这些操作都是通过给定参数的类型来决定的，但是它们有一种额外的特性-签名多态性（signature polymorphism），
 * 直接把这些调用连接到JVM执行栈上。
 * <p>
 * As is usual with virtual methods, source-level calls to access mode methods
 * compile to an {@code invokevirtual} instruction.  More unusually, the
 * compiler must record the actual argument types, and may not perform method
 * invocation conversions on the arguments.  Instead, it must generate
 * instructions to push them on the stack according to their own unconverted
 * types.  The VarHandle object itself will be pushed on the stack before the
 * arguments.  The compiler then generates an {@code invokevirtual} instruction
 * that invokes the access mode method with a symbolic type descriptor which
 * describes the argument and return types.
 * 和其他的虚拟方法一样，对访问模式方法的调用被虚拟机编译为invokevirtual指令。
 * 编译器必须记录实际参数类型，但是它不会对参数进行转换；相反，编译器必须生成指令，按照它们自己未转换之前的类型把它们推入执行栈中。
 * 并且，VarHandle对象将会在这些参数之前被推入栈，
 * 然后编译器生成一个invokevirtual指令来调用访问模式方法（通过一个类型描述符来确定参数和返回类型）
 * <p>
 * To issue a complete symbolic type descriptor, the compiler must also
 * determine the return type (if polymorphic).  This is based on a cast on the
 * method invocation expression, if there is one, or else {@code Object} if the
 * invocation is an expression, or else {@code void} if the invocation is a
 * statement.  The cast may be to a primitive type (but not {@code void}).
 * <p>
 * As a corner case, an uncasted {@code null} argument is given a symbolic type
 * descriptor of {@code java.lang.Void}.  The ambiguity with the type
 * {@code Void} is harmless, since there are no references of type {@code Void}
 * except the null reference.
 *
 *
 * <h1><a id="invoke">Performing invocation of access mode methods</a></h1>
 * 执行访问模式方法
 * The first time an {@code invokevirtual} instruction is executed it is linked
 * by symbolically resolving the names in the instruction and verifying that
 * the method call is statically legal.  This also holds for calls to access mode
 * methods.  In this case, the symbolic type descriptor emitted by the compiler
 * is checked for correct syntax, and names it contains are resolved.  Thus, an
 * {@code invokevirtual} instruction which invokes an access mode method will
 * always link, as long as the symbolic type descriptor is syntactically
 * well-formed and the types exist.
 * invokevirtual指令第一次执行时，会解析指令的名称并验证方法调用的合法性。
 * 这种方式同样适用于访问模式方法的调用，在这种情况下，编译器会检查类型描述符的正确性，并解析其包含的名称。
 * 因此，只要符号类型描述符语法正确并且类型存在，那么调用访问模式方法所生成的invokevirtual指令将始终链接。
 * <p>
 * When the {@code invokevirtual} is executed after linking, the receiving
 * VarHandle's access mode type is first checked by the JVM to ensure that it
 * matches the symbolic type descriptor.  If the type
 * match fails, it means that the access mode method which the caller is
 * invoking is not present on the individual VarHandle being invoked.
 * 当invokevirtual在链接后执行时，JVM首先会检查接收到的VarHandle的访问模式类型，保证它与类型描述符相匹配。
 * 如果类型匹配失败，则意味着VarHandle不存在此访问模式方法。
 * <p>
 * Invocation of an access mode method behaves as if an invocation of
 * {@link MethodHandle#invoke}, where the receiving method handle accepts the
 * VarHandle instance as the leading argument.  More specifically, the
 * following, where {@code {access-mode}} corresponds to the access mode method
 * name:
 * <pre> {@code
 * VarHandle vh = ..
 * R r = (R) vh.{access-mode}(p1, p2, ..., pN);
 * }</pre>
 * behaves as if:
 * <pre> {@code
 * VarHandle vh = ..
 * VarHandle.AccessMode am = VarHandle.AccessMode.valueFromMethodName("{access-mode}");
 * MethodHandle mh = MethodHandles.varHandleExactInvoker(
 *                       am,
 *                       vh.accessModeType(am));
 *
 * R r = (R) mh.invoke(vh, p1, p2, ..., pN)
 * }</pre>
 * (modulo access mode methods do not declare throwing of {@code Throwable}).
 * This is equivalent to:
 * <pre> {@code
 * MethodHandle mh = MethodHandles.lookup().findVirtual(
 *                       VarHandle.class,
 *                       "{access-mode}",
 *                       MethodType.methodType(R, p1, p2, ..., pN));
 *
 * R r = (R) mh.invokeExact(vh, p1, p2, ..., pN)
 * }</pre>
 * where the desired method type is the symbolic type descriptor and a
 * {@link MethodHandle#invokeExact} is performed, since before invocation of the
 * target, the handle will apply reference casts as necessary and box, unbox, or
 * widen primitive values, as if by {@link MethodHandle#asType asType} (see also
 * {@link MethodHandles#varHandleInvoker}).
 *
 * More concisely, such behaviour is equivalent to:
 * <pre> {@code
 * VarHandle vh = ..
 * VarHandle.AccessMode am = VarHandle.AccessMode.valueFromMethodName("{access-mode}");
 * MethodHandle mh = vh.toMethodHandle(am);
 *
 * R r = (R) mh.invoke(p1, p2, ..., pN)
 * }</pre>
 * Where, in this case, the method handle is bound to the VarHandle instance.
 *
 *
 * <h1>Invocation checking</h1>
 * 调用检查
 * In typical programs, VarHandle access mode type matching will usually
 * succeed.  But if a match fails, the JVM will throw a
 * {@link WrongMethodTypeException}.
 * 在标准程序中，VarHandle的访问模式类型都会匹配成功，但如果匹配失败，JVM就会抛出WrongMethodTypeException
 * <p>
 * Thus, an access mode type mismatch which might show up as a linkage error
 * in a statically typed program can show up as a dynamic
 * {@code WrongMethodTypeException} in a program which uses VarHandles.
 * 因此，访问模式类型不匹配在静态类型程序中将会呈现链接失败的错误，
 * 在使用VarHandle的程序中将会动态地抛出WrongMethodTypeException异常
 * <p>
 * Because access mode types contain "live" {@code Class} objects, method type
 * matching takes into account both type names and class loaders.
 * Thus, even if a VarHandle {@code VH} is created in one class loader
 * {@code L1} and used in another {@code L2}, VarHandle access mode method
 * calls are type-safe, because the caller's symbolic type descriptor, as
 * resolved in {@code L2}, is matched against the original callee method's
 * symbolic type descriptor, as resolved in {@code L1}.  The resolution in
 * {@code L1} happens when {@code VH} is created and its access mode types are
 * assigned, while the resolution in {@code L2} happens when the
 * {@code invokevirtual} instruction is linked.
 * 因为访问模式类型包含了活跃Class对象，方法类型匹配需要考虑类型名称和类加载器两个因素。
 * 如果VarHandle在一个类加载器L1中创建，在另外一个类加载器L2中使用，VarHandle访问模式方法的调用是类型安全的，
 * 因为在L2中解析的调用者的类型描述符与原始调用方法的类型描述符匹配。
 * L1的类型匹配发生在VH创建并且其访问模式类型被分配时，而L2的类型匹配发生在invokevirtual指令被链接时。
 * <p>
 * Apart from type descriptor checks, a VarHandles's capability to
 * access it's variables is unrestricted.
 * If a VarHandle is formed on a non-public variable by a class that has access
 * to that variable, the resulting VarHandle can be used in any place by any
 * caller who receives a reference to it.
 * 除了类型描述符检查，VarHandles访问它的变量是不受任何限制的。
 * todo 如果一个VarHandle被声明为non-public变量类型，那么这个VarHandle可以被任何接收到它的引用的调用者使用
 * <p>
 * Unlike with the Core Reflection API, where access is checked every time a
 * reflective method is invoked, VarHandle access checking is performed
 * <a href="MethodHandles.Lookup.html#access">when the VarHandle is
 * created</a>.
 * Thus, VarHandles to non-public variables, or to variables in non-public
 * classes, should generally be kept secret.  They should not be passed to
 * untrusted code unless their use from the untrusted code would be harmless.
 * 核心的反射API在每一次方法被调用时都会进行访问检查，而VarHandle的访问检查是在VarHandle被创建时进行的。
 * 所以，VarHandle对于non-public变量或non-public类的变量应该保持隐秘。
 * 它们不应该被传递给不可信的代码，除非这些代码对程序是无害的。
 *
 * <h1>VarHandle creation</h1>
 * VarHandle的创建
 * Java code can create a VarHandle that directly accesses any field that is
 * accessible to that code.  This is done via a reflective, capability-based
 * API called {@link java.lang.invoke.MethodHandles.Lookup
 * MethodHandles.Lookup}.
 * For example, a VarHandle for a non-static field can be obtained
 * from {@link java.lang.invoke.MethodHandles.Lookup#findVarHandle
 * Lookup.findVarHandle}.
 * There is also a conversion method from Core Reflection API objects,
 * {@link java.lang.invoke.MethodHandles.Lookup#unreflectVarHandle
 * Lookup.unreflectVarHandle}.
 * <p>
 * Access to protected field members is restricted to receivers only of the
 * accessing class, or one of its subclasses, and the accessing class must in
 * turn be a subclass (or package sibling) of the protected member's defining
 * class.  If a VarHandle refers to a protected non-static field of a declaring
 * class outside the current package, the receiver argument will be narrowed to
 * the type of the accessing class.
 * 访问受保护的字段成员仅限于：接收者包括访问类或者是访问类的子类，并且访问类必须是受保护成员定义类的子类。
 * 如果VarHandle引用了当前包之外的一个受保护的非静态字段，接收者参数将被缩小到访问类的类型。
 *
 * <h1>Interoperation between VarHandles and the Core Reflection API</h1>
 * VarHandles和核心API的相互配合
 * Using factory methods in the {@link java.lang.invoke.MethodHandles.Lookup
 * Lookup} API, any field represented by a Core Reflection API object
 * can be converted to a behaviorally equivalent VarHandle.
 * For example, a reflective {@link java.lang.reflect.Field Field} can
 * be converted to a VarHandle using
 * {@link java.lang.invoke.MethodHandles.Lookup#unreflectVarHandle
 * Lookup.unreflectVarHandle}.
 * The resulting VarHandles generally provide more direct and efficient
 * access to the underlying fields.
 * <p>
 * As a special case, when the Core Reflection API is used to view the
 * signature polymorphic access mode methods in this class, they appear as
 * ordinary non-polymorphic methods.  Their reflective appearance, as viewed by
 * {@link java.lang.Class#getDeclaredMethod Class.getDeclaredMethod},
 * is unaffected by their special status in this API.
 * For example, {@link java.lang.reflect.Method#getModifiers
 * Method.getModifiers}
 * will report exactly those modifier bits required for any similarly
 * declared method, including in this case {@code native} and {@code varargs}
 * bits.
 * <p>
 * As with any reflected method, these methods (when reflected) may be invoked
 * directly via {@link java.lang.reflect.Method#invoke java.lang.reflect.Method.invoke},
 * via JNI, or indirectly via
 * {@link java.lang.invoke.MethodHandles.Lookup#unreflect Lookup.unreflect}.
 * However, such reflective calls do not result in access mode method
 * invocations.  Such a call, if passed the required argument (a single one, of
 * type {@code Object[]}), will ignore the argument and will throw an
 * {@code UnsupportedOperationException}.
 * <p>
 * Since {@code invokevirtual} instructions can natively invoke VarHandle
 * access mode methods under any symbolic type descriptor, this reflective view
 * conflicts with the normal presentation of these methods via bytecodes.
 * Thus, these native methods, when reflectively viewed by
 * {@code Class.getDeclaredMethod}, may be regarded as placeholders only.
 * 由于invokevirtual指令可以在任何类型描述符下天然地调用VarHandle的访问模式方法，
 * 这种反射查看的方式与通过字节码实现的方法相冲突。
 * 因此，这些本地方法被Class.getDeclaredMethod反射查看时，可能仅被当做占位符
 * <p>
 * In order to obtain an invoker method for a particular access mode type,
 * use {@link java.lang.invoke.MethodHandles#varHandleExactInvoker} or
 * {@link java.lang.invoke.MethodHandles#varHandleInvoker}.  The
 * {@link java.lang.invoke.MethodHandles.Lookup#findVirtual Lookup.findVirtual}
 * API is also able to return a method handle to call an access mode method for
 * any specified access mode type and is equivalent in behaviour to
 * {@link java.lang.invoke.MethodHandles#varHandleInvoker}.
 *
 * <h1>Interoperation between VarHandles and Java generics</h1>
 * VarHandles与java泛型的相互配合
 * A VarHandle can be obtained for a variable, such as a a field, which is
 * declared with Java generic types.  As with the Core Reflection API, the
 * VarHandle's variable type will be constructed from the erasure of the
 * source-level type.  When a VarHandle access mode method is invoked, the
 * types
 * of its arguments or the return value cast type may be generic types or type
 * instances.  If this occurs, the compiler will replace those types by their
 * erasures when it constructs the symbolic type descriptor for the
 * {@code invokevirtual} instruction.
 * VarHandle可以作为一个变量被获取，比如a字段，并通过java泛型类型来声明。
 * 与核心API一样，VarHandle的变量类型将通过消除源类型来构造。
 * 当VarHandle访问模式方法被调用时，它的参数类型或返回类型可以转换为泛型类型或实例类型。
 * 如果这种情况发生，当编译器为invokevirtual指令构造符号类型描述符时，编译器将会用它们的擦除类型来代替这些类型。
 *
 * @see MethodHandle
 * @see MethodHandles
 * @see MethodType
 * @since 9
 */
public abstract class VarHandle {
    final VarForm vform;

    VarHandle(VarForm vform) {
        this.vform = vform;
    }

    RuntimeException unsupported() {
        return new UnsupportedOperationException();
    }

    // Plain accessors

    /**
     * Returns the value of a variable, with memory semantics of reading as
     * if the variable was declared non-{@code volatile}.  Commonly referred to
     * as plain read access.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code get}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.GET)} on this VarHandle.
     *
     * <p>This access mode is supported by all VarHandle instances and never
     * throws {@code UnsupportedOperationException}.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the value of the
     * variable
     * , statically represented using {@code Object}.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object get(Object... args);

    /**
     * Sets the value of a variable to the {@code newValue}, with memory
     * semantics of setting as if the variable was declared non-{@code volatile}
     * and non-{@code final}.  Commonly referred to as plain write access.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T newValue)void}
     *
     * <p>The symbolic type descriptor at the call site of {@code set}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.SET)} on this VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T newValue)}
     * , statically represented using varargs.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    void set(Object... args);


    // Volatile accessors

    /**
     * Returns the value of a variable, with memory semantics of reading as if
     * the variable was declared {@code volatile}.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code getVolatile}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.GET_VOLATILE)} on this
     * VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the value of the
     * variable
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object getVolatile(Object... args);

    /**
     * Sets the value of a variable to the {@code newValue}, with memory
     * semantics of setting as if the variable was declared {@code volatile}.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T newValue)void}.
     *
     * <p>The symbolic type descriptor at the call site of {@code setVolatile}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.SET_VOLATILE)} on this
     * VarHandle.
     *
     * @apiNote
     * Ignoring the many semantic differences from C and C++, this method has
     * memory ordering effects compatible with {@code memory_order_seq_cst}.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T newValue)}
     * , statically represented using varargs.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    void setVolatile(Object... args);


    /**
     * Returns the value of a variable, accessed in program order, but with no
     * assurance of memory ordering effects with respect to other threads.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code getOpaque}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.GET_OPAQUE)} on this
     * VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the value of the
     * variable
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object getOpaque(Object... args);

    /**
     * Sets the value of a variable to the {@code newValue}, in program order,
     * but with no assurance of memory ordering effects with respect to other
     * threads.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T newValue)void}.
     *
     * <p>The symbolic type descriptor at the call site of {@code setOpaque}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.SET_OPAQUE)} on this
     * VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T newValue)}
     * , statically represented using varargs.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    void setOpaque(Object... args);


    // Lazy accessors

    /**
     * Returns the value of a variable, and ensures that subsequent loads and
     * stores are not reordered before this access.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code getAcquire}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.GET_ACQUIRE)} on this
     * VarHandle.
     *
     * @apiNote
     * Ignoring the many semantic differences from C and C++, this method has
     * memory ordering effects compatible with {@code memory_order_acquire}
     * ordering.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the value of the
     * variable
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object getAcquire(Object... args);

    /**
     * Sets the value of a variable to the {@code newValue}, and ensures that
     * prior loads and stores are not reordered after this access.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T newValue)void}.
     *
     * <p>The symbolic type descriptor at the call site of {@code setRelease}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.SET_RELEASE)} on this
     * VarHandle.
     *
     * @apiNote
     * Ignoring the many semantic differences from C and C++, this method has
     * memory ordering effects compatible with {@code memory_order_release}
     * ordering.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T newValue)}
     * , statically represented using varargs.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    void setRelease(Object... args);


    // Compare and set accessors

    /**
     * Atomically sets the value of a variable to the {@code newValue} with the
     * memory semantics of {@link #setVolatile} if the variable's current value,
     * referred to as the <em>witness value</em>, {@code ==} the
     * {@code expectedValue}, as accessed with the memory semantics of
     * {@link #getVolatile}.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T expectedValue, T newValue)boolean}.
     *
     * <p>The symbolic type descriptor at the call site of {@code
     * compareAndSet} must match the access mode type that is the result of
     * calling {@code accessModeType(VarHandle.AccessMode.COMPARE_AND_SET)} on
     * this VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T expectedValue, T newValue)}
     * , statically represented using varargs.
     * @return {@code true} if successful, otherwise {@code false} if the
     * witness value was not the same as the {@code expectedValue}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #setVolatile(Object...)
     * @see #getVolatile(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    boolean compareAndSet(Object... args);

    /**
     * Atomically sets the value of a variable to the {@code newValue} with the
     * memory semantics of {@link #setVolatile} if the variable's current value,
     * referred to as the <em>witness value</em>, {@code ==} the
     * {@code expectedValue}, as accessed with the memory semantics of
     * {@link #getVolatile}.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T expectedValue, T newValue)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code
     * compareAndExchange}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.COMPARE_AND_EXCHANGE)}
     * on this VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T expectedValue, T newValue)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the witness value, which
     * will be the same as the {@code expectedValue} if successful
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type is not
     * compatible with the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type is compatible with the
     * caller's symbolic type descriptor, but a reference cast fails.
     * @see #setVolatile(Object...)
     * @see #getVolatile(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object compareAndExchange(Object... args);

    /**
     * Atomically sets the value of a variable to the {@code newValue} with the
     * memory semantics of {@link #set} if the variable's current value,
     * referred to as the <em>witness value</em>, {@code ==} the
     * {@code expectedValue}, as accessed with the memory semantics of
     * {@link #getAcquire}.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T expectedValue, T newValue)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code
     * compareAndExchangeAcquire}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.COMPARE_AND_EXCHANGE_ACQUIRE)} on
     * this VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T expectedValue, T newValue)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the witness value, which
     * will be the same as the {@code expectedValue} if successful
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #set(Object...)
     * @see #getAcquire(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object compareAndExchangeAcquire(Object... args);

    /**
     * Atomically sets the value of a variable to the {@code newValue} with the
     * memory semantics of {@link #setRelease} if the variable's current value,
     * referred to as the <em>witness value</em>, {@code ==} the
     * {@code expectedValue}, as accessed with the memory semantics of
     * {@link #get}.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T expectedValue, T newValue)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code
     * compareAndExchangeRelease}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.COMPARE_AND_EXCHANGE_RELEASE)}
     * on this VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T expectedValue, T newValue)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the witness value, which
     * will be the same as the {@code expectedValue} if successful
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #setRelease(Object...)
     * @see #get(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object compareAndExchangeRelease(Object... args);

    // Weak (spurious failures allowed)

    /**
     * Possibly atomically sets the value of a variable to the {@code newValue}
     * with the semantics of {@link #set} if the variable's current value,
     * referred to as the <em>witness value</em>, {@code ==} the
     * {@code expectedValue}, as accessed with the memory semantics of
     * {@link #get}.
     *
     * <p>This operation may fail spuriously (typically, due to memory
     * contention) even if the witness value does match the expected value.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T expectedValue, T newValue)boolean}.
     *
     * <p>The symbolic type descriptor at the call site of {@code
     * weakCompareAndSetPlain} must match the access mode type that is the result of
     * calling {@code accessModeType(VarHandle.AccessMode.WEAK_COMPARE_AND_SET_PLAIN)}
     * on this VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T expectedValue, T newValue)}
     * , statically represented using varargs.
     * @return {@code true} if successful, otherwise {@code false} if the
     * witness value was not the same as the {@code expectedValue} or if this
     * operation spuriously failed.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #set(Object...)
     * @see #get(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    boolean weakCompareAndSetPlain(Object... args);

    /**
     * Possibly atomically sets the value of a variable to the {@code newValue}
     * with the memory semantics of {@link #setVolatile} if the variable's
     * current value, referred to as the <em>witness value</em>, {@code ==} the
     * {@code expectedValue}, as accessed with the memory semantics of
     * {@link #getVolatile}.
     *
     * <p>This operation may fail spuriously (typically, due to memory
     * contention) even if the witness value does match the expected value.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T expectedValue, T newValue)boolean}.
     *
     * <p>The symbolic type descriptor at the call site of {@code
     * weakCompareAndSet} must match the access mode type that is the
     * result of calling {@code accessModeType(VarHandle.AccessMode.WEAK_COMPARE_AND_SET)}
     * on this VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T expectedValue, T newValue)}
     * , statically represented using varargs.
     * @return {@code true} if successful, otherwise {@code false} if the
     * witness value was not the same as the {@code expectedValue} or if this
     * operation spuriously failed.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #setVolatile(Object...)
     * @see #getVolatile(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    boolean weakCompareAndSet(Object... args);

    /**
     * Possibly atomically sets the value of a variable to the {@code newValue}
     * with the semantics of {@link #set} if the variable's current value,
     * referred to as the <em>witness value</em>, {@code ==} the
     * {@code expectedValue}, as accessed with the memory semantics of
     * {@link #getAcquire}.
     *
     * <p>This operation may fail spuriously (typically, due to memory
     * contention) even if the witness value does match the expected value.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T expectedValue, T newValue)boolean}.
     *
     * <p>The symbolic type descriptor at the call site of {@code
     * weakCompareAndSetAcquire}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.WEAK_COMPARE_AND_SET_ACQUIRE)}
     * on this VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T expectedValue, T newValue)}
     * , statically represented using varargs.
     * @return {@code true} if successful, otherwise {@code false} if the
     * witness value was not the same as the {@code expectedValue} or if this
     * operation spuriously failed.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #set(Object...)
     * @see #getAcquire(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    boolean weakCompareAndSetAcquire(Object... args);

    /**
     * Possibly atomically sets the value of a variable to the {@code newValue}
     * with the semantics of {@link #setRelease} if the variable's current
     * value, referred to as the <em>witness value</em>, {@code ==} the
     * {@code expectedValue}, as accessed with the memory semantics of
     * {@link #get}.
     *
     * <p>This operation may fail spuriously (typically, due to memory
     * contention) even if the witness value does match the expected value.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T expectedValue, T newValue)boolean}.
     *
     * <p>The symbolic type descriptor at the call site of {@code
     * weakCompareAndSetRelease}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.WEAK_COMPARE_AND_SET_RELEASE)}
     * on this VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T expectedValue, T newValue)}
     * , statically represented using varargs.
     * @return {@code true} if successful, otherwise {@code false} if the
     * witness value was not the same as the {@code expectedValue} or if this
     * operation spuriously failed.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #setRelease(Object...)
     * @see #get(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    boolean weakCompareAndSetRelease(Object... args);

    /**
     * Atomically sets the value of a variable to the {@code newValue} with the
     * memory semantics of {@link #setVolatile} and returns the variable's
     * previous value, as accessed with the memory semantics of
     * {@link #getVolatile}.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T newValue)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code getAndSet}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.GET_AND_SET)} on this
     * VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T newValue)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the previous value of
     * the variable
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #setVolatile(Object...)
     * @see #getVolatile(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object getAndSet(Object... args);

    /**
     * Atomically sets the value of a variable to the {@code newValue} with the
     * memory semantics of {@link #set} and returns the variable's
     * previous value, as accessed with the memory semantics of
     * {@link #getAcquire}.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T newValue)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code getAndSetAcquire}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.GET_AND_SET_ACQUIRE)} on this
     * VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T newValue)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the previous value of
     * the variable
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #setVolatile(Object...)
     * @see #getVolatile(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object getAndSetAcquire(Object... args);

    /**
     * Atomically sets the value of a variable to the {@code newValue} with the
     * memory semantics of {@link #setRelease} and returns the variable's
     * previous value, as accessed with the memory semantics of
     * {@link #get}.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T newValue)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code getAndSetRelease}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.GET_AND_SET_RELEASE)} on this
     * VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T newValue)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the previous value of
     * the variable
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #setVolatile(Object...)
     * @see #getVolatile(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object getAndSetRelease(Object... args);

    // Primitive adders
    // Throw UnsupportedOperationException for refs

    /**
     * Atomically adds the {@code value} to the current value of a variable with
     * the memory semantics of {@link #setVolatile}, and returns the variable's
     * previous value, as accessed with the memory semantics of
     * {@link #getVolatile}.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T value)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code getAndAdd}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.GET_AND_ADD)} on this
     * VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T value)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the previous value of
     * the variable
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #setVolatile(Object...)
     * @see #getVolatile(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object getAndAdd(Object... args);

    /**
     * Atomically adds the {@code value} to the current value of a variable with
     * the memory semantics of {@link #set}, and returns the variable's
     * previous value, as accessed with the memory semantics of
     * {@link #getAcquire}.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T value)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code getAndAddAcquire}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.GET_AND_ADD_ACQUIRE)} on this
     * VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T value)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the previous value of
     * the variable
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #setVolatile(Object...)
     * @see #getVolatile(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object getAndAddAcquire(Object... args);

    /**
     * Atomically adds the {@code value} to the current value of a variable with
     * the memory semantics of {@link #setRelease}, and returns the variable's
     * previous value, as accessed with the memory semantics of
     * {@link #get}.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T value)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code getAndAddRelease}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.GET_AND_ADD_RELEASE)} on this
     * VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T value)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the previous value of
     * the variable
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #setVolatile(Object...)
     * @see #getVolatile(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object getAndAddRelease(Object... args);


    // Bitwise operations
    // Throw UnsupportedOperationException for refs

    /**
     * Atomically sets the value of a variable to the result of
     * bitwise OR between the variable's current value and the {@code mask}
     * with the memory semantics of {@link #setVolatile} and returns the
     * variable's previous value, as accessed with the memory semantics of
     * {@link #getVolatile}.
     *
     * <p>If the variable type is the non-integral {@code boolean} type then a
     * logical OR is performed instead of a bitwise OR.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T mask)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code getAndBitwiseOr}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.GET_AND_BITWISE_OR)} on this
     * VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T mask)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the previous value of
     * the variable
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #setVolatile(Object...)
     * @see #getVolatile(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object getAndBitwiseOr(Object... args);

    /**
     * Atomically sets the value of a variable to the result of
     * bitwise OR between the variable's current value and the {@code mask}
     * with the memory semantics of {@link #set} and returns the
     * variable's previous value, as accessed with the memory semantics of
     * {@link #getAcquire}.
     *
     * <p>If the variable type is the non-integral {@code boolean} type then a
     * logical OR is performed instead of a bitwise OR.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T mask)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code getAndBitwiseOrAcquire}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.GET_AND_BITWISE_OR_ACQUIRE)} on this
     * VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T mask)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the previous value of
     * the variable
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #set(Object...)
     * @see #getAcquire(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object getAndBitwiseOrAcquire(Object... args);

    /**
     * Atomically sets the value of a variable to the result of
     * bitwise OR between the variable's current value and the {@code mask}
     * with the memory semantics of {@link #setRelease} and returns the
     * variable's previous value, as accessed with the memory semantics of
     * {@link #get}.
     *
     * <p>If the variable type is the non-integral {@code boolean} type then a
     * logical OR is performed instead of a bitwise OR.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T mask)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code getAndBitwiseOrRelease}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.GET_AND_BITWISE_OR_RELEASE)} on this
     * VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T mask)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the previous value of
     * the variable
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #setRelease(Object...)
     * @see #get(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object getAndBitwiseOrRelease(Object... args);

    /**
     * Atomically sets the value of a variable to the result of
     * bitwise AND between the variable's current value and the {@code mask}
     * with the memory semantics of {@link #setVolatile} and returns the
     * variable's previous value, as accessed with the memory semantics of
     * {@link #getVolatile}.
     *
     * <p>If the variable type is the non-integral {@code boolean} type then a
     * logical AND is performed instead of a bitwise AND.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T mask)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code getAndBitwiseAnd}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.GET_AND_BITWISE_AND)} on this
     * VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T mask)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the previous value of
     * the variable
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #setVolatile(Object...)
     * @see #getVolatile(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object getAndBitwiseAnd(Object... args);

    /**
     * Atomically sets the value of a variable to the result of
     * bitwise AND between the variable's current value and the {@code mask}
     * with the memory semantics of {@link #set} and returns the
     * variable's previous value, as accessed with the memory semantics of
     * {@link #getAcquire}.
     *
     * <p>If the variable type is the non-integral {@code boolean} type then a
     * logical AND is performed instead of a bitwise AND.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T mask)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code getAndBitwiseAndAcquire}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.GET_AND_BITWISE_AND_ACQUIRE)} on this
     * VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T mask)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the previous value of
     * the variable
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #set(Object...)
     * @see #getAcquire(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object getAndBitwiseAndAcquire(Object... args);

    /**
     * Atomically sets the value of a variable to the result of
     * bitwise AND between the variable's current value and the {@code mask}
     * with the memory semantics of {@link #setRelease} and returns the
     * variable's previous value, as accessed with the memory semantics of
     * {@link #get}.
     *
     * <p>If the variable type is the non-integral {@code boolean} type then a
     * logical AND is performed instead of a bitwise AND.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T mask)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code getAndBitwiseAndRelease}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.GET_AND_BITWISE_AND_RELEASE)} on this
     * VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T mask)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the previous value of
     * the variable
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #setRelease(Object...)
     * @see #get(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object getAndBitwiseAndRelease(Object... args);

    /**
     * Atomically sets the value of a variable to the result of
     * bitwise XOR between the variable's current value and the {@code mask}
     * with the memory semantics of {@link #setVolatile} and returns the
     * variable's previous value, as accessed with the memory semantics of
     * {@link #getVolatile}.
     *
     * <p>If the variable type is the non-integral {@code boolean} type then a
     * logical XOR is performed instead of a bitwise XOR.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T mask)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code getAndBitwiseXor}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.GET_AND_BITWISE_XOR)} on this
     * VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T mask)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the previous value of
     * the variable
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #setVolatile(Object...)
     * @see #getVolatile(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object getAndBitwiseXor(Object... args);

    /**
     * Atomically sets the value of a variable to the result of
     * bitwise XOR between the variable's current value and the {@code mask}
     * with the memory semantics of {@link #set} and returns the
     * variable's previous value, as accessed with the memory semantics of
     * {@link #getAcquire}.
     *
     * <p>If the variable type is the non-integral {@code boolean} type then a
     * logical XOR is performed instead of a bitwise XOR.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T mask)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code getAndBitwiseXorAcquire}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.GET_AND_BITWISE_XOR_ACQUIRE)} on this
     * VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T mask)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the previous value of
     * the variable
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #set(Object...)
     * @see #getAcquire(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object getAndBitwiseXorAcquire(Object... args);

    /**
     * Atomically sets the value of a variable to the result of
     * bitwise XOR between the variable's current value and the {@code mask}
     * with the memory semantics of {@link #setRelease} and returns the
     * variable's previous value, as accessed with the memory semantics of
     * {@link #get}.
     *
     * <p>If the variable type is the non-integral {@code boolean} type then a
     * logical XOR is performed instead of a bitwise XOR.
     *
     * <p>The method signature is of the form {@code (CT1 ct1, ..., CTn ctn, T mask)T}.
     *
     * <p>The symbolic type descriptor at the call site of {@code getAndBitwiseXorRelease}
     * must match the access mode type that is the result of calling
     * {@code accessModeType(VarHandle.AccessMode.GET_AND_BITWISE_XOR_RELEASE)} on this
     * VarHandle.
     *
     * @param args the signature-polymorphic parameter list of the form
     * {@code (CT1 ct1, ..., CTn ctn, T mask)}
     * , statically represented using varargs.
     * @return the signature-polymorphic result that is the previous value of
     * the variable
     * , statically represented using {@code Object}.
     * @throws UnsupportedOperationException if the access mode is unsupported
     * for this VarHandle.
     * @throws WrongMethodTypeException if the access mode type does not
     * match the caller's symbolic type descriptor.
     * @throws ClassCastException if the access mode type matches the caller's
     * symbolic type descriptor, but a reference cast fails.
     * @see #setRelease(Object...)
     * @see #get(Object...)
     */
    public final native
    @MethodHandle.PolymorphicSignature
    @HotSpotIntrinsicCandidate
    Object getAndBitwiseXorRelease(Object... args);


    enum AccessType {
        GET(Object.class),
        SET(void.class),
        COMPARE_AND_SWAP(boolean.class),
        COMPARE_AND_EXCHANGE(Object.class),
        GET_AND_UPDATE(Object.class);

        final Class<?> returnType;
        final boolean isMonomorphicInReturnType;

        AccessType(Class<?> returnType) {
            this.returnType = returnType;
            isMonomorphicInReturnType = returnType != Object.class;
        }

        MethodType accessModeType(Class<?> receiver, Class<?> value,
                                  Class<?>... intermediate) {
            Class<?>[] ps;
            int i;
            switch (this) {
                case GET:
                    ps = allocateParameters(0, receiver, intermediate);
                    fillParameters(ps, receiver, intermediate);
                    return MethodType.methodType(value, ps);
                case SET:
                    ps = allocateParameters(1, receiver, intermediate);
                    i = fillParameters(ps, receiver, intermediate);
                    ps[i] = value;
                    return MethodType.methodType(void.class, ps);
                case COMPARE_AND_SWAP:
                    ps = allocateParameters(2, receiver, intermediate);
                    i = fillParameters(ps, receiver, intermediate);
                    ps[i++] = value;
                    ps[i] = value;
                    return MethodType.methodType(boolean.class, ps);
                case COMPARE_AND_EXCHANGE:
                    ps = allocateParameters(2, receiver, intermediate);
                    i = fillParameters(ps, receiver, intermediate);
                    ps[i++] = value;
                    ps[i] = value;
                    return MethodType.methodType(value, ps);
                case GET_AND_UPDATE:
                    ps = allocateParameters(1, receiver, intermediate);
                    i = fillParameters(ps, receiver, intermediate);
                    ps[i] = value;
                    return MethodType.methodType(value, ps);
                default:
                    throw new InternalError("Unknown AccessType");
            }
        }

        private static Class<?>[] allocateParameters(int values,
                                                     Class<?> receiver, Class<?>... intermediate) {
            int size = ((receiver != null) ? 1 : 0) + intermediate.length + values;
            return new Class<?>[size];
        }

        private static int fillParameters(Class<?>[] ps,
                                          Class<?> receiver, Class<?>... intermediate) {
            int i = 0;
            if (receiver != null)
                ps[i++] = receiver;
            for (int j = 0; j < intermediate.length; j++)
                ps[i++] = intermediate[j];
            return i;
        }
    }

    /**
     * The set of access modes that specify how a variable, referenced by a
     * VarHandle, is accessed.
     */
    public enum AccessMode {
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#get VarHandle.get}
         */
        GET("get", AccessType.GET),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#set VarHandle.set}
         */
        SET("set", AccessType.SET),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#getVolatile VarHandle.getVolatile}
         */
        GET_VOLATILE("getVolatile", AccessType.GET),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#setVolatile VarHandle.setVolatile}
         */
        SET_VOLATILE("setVolatile", AccessType.SET),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#getAcquire VarHandle.getAcquire}
         */
        GET_ACQUIRE("getAcquire", AccessType.GET),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#setRelease VarHandle.setRelease}
         */
        SET_RELEASE("setRelease", AccessType.SET),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#getOpaque VarHandle.getOpaque}
         */
        GET_OPAQUE("getOpaque", AccessType.GET),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#setOpaque VarHandle.setOpaque}
         */
        SET_OPAQUE("setOpaque", AccessType.SET),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#compareAndSet VarHandle.compareAndSet}
         */
        COMPARE_AND_SET("compareAndSet", AccessType.COMPARE_AND_SWAP),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#compareAndExchange VarHandle.compareAndExchange}
         */
        COMPARE_AND_EXCHANGE("compareAndExchange", AccessType.COMPARE_AND_EXCHANGE),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#compareAndExchangeAcquire VarHandle.compareAndExchangeAcquire}
         */
        COMPARE_AND_EXCHANGE_ACQUIRE("compareAndExchangeAcquire", AccessType.COMPARE_AND_EXCHANGE),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#compareAndExchangeRelease VarHandle.compareAndExchangeRelease}
         */
        COMPARE_AND_EXCHANGE_RELEASE("compareAndExchangeRelease", AccessType.COMPARE_AND_EXCHANGE),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#weakCompareAndSetPlain VarHandle.weakCompareAndSetPlain}
         */
        WEAK_COMPARE_AND_SET_PLAIN("weakCompareAndSetPlain", AccessType.COMPARE_AND_SWAP),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#weakCompareAndSet VarHandle.weakCompareAndSet}
         */
        WEAK_COMPARE_AND_SET("weakCompareAndSet", AccessType.COMPARE_AND_SWAP),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#weakCompareAndSetAcquire VarHandle.weakCompareAndSetAcquire}
         */
        WEAK_COMPARE_AND_SET_ACQUIRE("weakCompareAndSetAcquire", AccessType.COMPARE_AND_SWAP),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#weakCompareAndSetRelease VarHandle.weakCompareAndSetRelease}
         */
        WEAK_COMPARE_AND_SET_RELEASE("weakCompareAndSetRelease", AccessType.COMPARE_AND_SWAP),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#getAndSet VarHandle.getAndSet}
         */
        GET_AND_SET("getAndSet", AccessType.GET_AND_UPDATE),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#getAndSetAcquire VarHandle.getAndSetAcquire}
         */
        GET_AND_SET_ACQUIRE("getAndSetAcquire", AccessType.GET_AND_UPDATE),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#getAndSetRelease VarHandle.getAndSetRelease}
         */
        GET_AND_SET_RELEASE("getAndSetRelease", AccessType.GET_AND_UPDATE),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#getAndAdd VarHandle.getAndAdd}
         */
        GET_AND_ADD("getAndAdd", AccessType.GET_AND_UPDATE),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#getAndAddAcquire VarHandle.getAndAddAcquire}
         */
        GET_AND_ADD_ACQUIRE("getAndAddAcquire", AccessType.GET_AND_UPDATE),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#getAndAddRelease VarHandle.getAndAddRelease}
         */
        GET_AND_ADD_RELEASE("getAndAddRelease", AccessType.GET_AND_UPDATE),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#getAndBitwiseOr VarHandle.getAndBitwiseOr}
         */
        GET_AND_BITWISE_OR("getAndBitwiseOr", AccessType.GET_AND_UPDATE),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#getAndBitwiseOrRelease VarHandle.getAndBitwiseOrRelease}
         */
        GET_AND_BITWISE_OR_RELEASE("getAndBitwiseOrRelease", AccessType.GET_AND_UPDATE),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#getAndBitwiseOrAcquire VarHandle.getAndBitwiseOrAcquire}
         */
        GET_AND_BITWISE_OR_ACQUIRE("getAndBitwiseOrAcquire", AccessType.GET_AND_UPDATE),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#getAndBitwiseAnd VarHandle.getAndBitwiseAnd}
         */
        GET_AND_BITWISE_AND("getAndBitwiseAnd", AccessType.GET_AND_UPDATE),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#getAndBitwiseAndRelease VarHandle.getAndBitwiseAndRelease}
         */
        GET_AND_BITWISE_AND_RELEASE("getAndBitwiseAndRelease", AccessType.GET_AND_UPDATE),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#getAndBitwiseAndAcquire VarHandle.getAndBitwiseAndAcquire}
         */
        GET_AND_BITWISE_AND_ACQUIRE("getAndBitwiseAndAcquire", AccessType.GET_AND_UPDATE),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#getAndBitwiseXor VarHandle.getAndBitwiseXor}
         */
        GET_AND_BITWISE_XOR("getAndBitwiseXor", AccessType.GET_AND_UPDATE),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#getAndBitwiseXorRelease VarHandle.getAndBitwiseXorRelease}
         */
        GET_AND_BITWISE_XOR_RELEASE("getAndBitwiseXorRelease", AccessType.GET_AND_UPDATE),
        /**
         * The access mode whose access is specified by the corresponding
         * method
         * {@link VarHandle#getAndBitwiseXorAcquire VarHandle.getAndBitwiseXorAcquire}
         */
        GET_AND_BITWISE_XOR_ACQUIRE("getAndBitwiseXorAcquire", AccessType.GET_AND_UPDATE),
        ;

        static final Map<String, AccessMode> methodNameToAccessMode;
        static {
            // Initial capacity of # values is sufficient to avoid resizes
            // for the smallest table size (32)
            methodNameToAccessMode = new HashMap<>(AccessMode.values().length);
            for (AccessMode am : AccessMode.values()) {
                methodNameToAccessMode.put(am.methodName, am);
            }
        }

        final String methodName;
        final AccessType at;

        AccessMode(final String methodName, AccessType at) {
            this.methodName = methodName;
            this.at = at;
        }

        /**
         * Returns the {@code VarHandle} signature-polymorphic method name
         * associated with this {@code AccessMode} value.
         *
         * @return the signature-polymorphic method name
         * @see #valueFromMethodName
         */
        public String methodName() {
            return methodName;
        }

        /**
         * Returns the {@code AccessMode} value associated with the specified
         * {@code VarHandle} signature-polymorphic method name.
         *
         * @param methodName the signature-polymorphic method name
         * @return the {@code AccessMode} value
         * @throws IllegalArgumentException if there is no {@code AccessMode}
         *         value associated with method name (indicating the method
         *         name does not correspond to a {@code VarHandle}
         *         signature-polymorphic method name).
         * @see #methodName
         */
        public static AccessMode valueFromMethodName(String methodName) {
            AccessMode am = methodNameToAccessMode.get(methodName);
            if (am != null) return am;
            throw new IllegalArgumentException("No AccessMode value for method name " + methodName);
        }

        @ForceInline
        static MemberName getMemberName(int ordinal, VarForm vform) {
            return vform.memberName_table[ordinal];
        }
    }

    static final class AccessDescriptor {
        final MethodType symbolicMethodTypeErased;
        final MethodType symbolicMethodTypeInvoker;
        final Class<?> returnType;
        final int type;
        final int mode;

        public AccessDescriptor(MethodType symbolicMethodType, int type, int mode) {
            this.symbolicMethodTypeErased = symbolicMethodType.erase();
            this.symbolicMethodTypeInvoker = symbolicMethodType.insertParameterTypes(0, VarHandle.class);
            this.returnType = symbolicMethodType.returnType();
            this.type = type;
            this.mode = mode;
        }
    }

    /**
     * Returns the variable type of variables referenced by this VarHandle.
     *
     * @return the variable type of variables referenced by this VarHandle
     */
    public final Class<?> varType() {
        MethodType typeSet = accessModeType(AccessMode.SET);
        return typeSet.parameterType(typeSet.parameterCount() - 1);
    }

    /**
     * Returns the coordinate types for this VarHandle.
     *
     * @return the coordinate types for this VarHandle. The returned
     * list is unmodifiable
     */
    public final List<Class<?>> coordinateTypes() {
        MethodType typeGet = accessModeType(AccessMode.GET);
        return typeGet.parameterList();
    }

    /**
     * Obtains the access mode type for this VarHandle and a given access mode.
     *
     * <p>The access mode type's parameter types will consist of a prefix that
     * is the coordinate types of this VarHandle followed by further
     * types as defined by the access mode method.
     * The access mode type's return type is defined by the return type of the
     * access mode method.
     *
     * @param accessMode the access mode, corresponding to the
     * signature-polymorphic method of the same name
     * @return the access mode type for the given access mode
     */
    public final MethodType accessModeType(AccessMode accessMode) {
        TypesAndInvokers tis = getTypesAndInvokers();
        MethodType mt = tis.methodType_table[accessMode.at.ordinal()];
        if (mt == null) {
            mt = tis.methodType_table[accessMode.at.ordinal()] =
                    accessModeTypeUncached(accessMode);
        }
        return mt;
    }
    abstract MethodType accessModeTypeUncached(AccessMode accessMode);

    /**
     * Returns {@code true} if the given access mode is supported, otherwise
     * {@code false}.
     *
     * <p>The return of a {@code false} value for a given access mode indicates
     * that an {@code UnsupportedOperationException} is thrown on invocation
     * of the corresponding access mode method.
     *
     * @param accessMode the access mode, corresponding to the
     * signature-polymorphic method of the same name
     * @return {@code true} if the given access mode is supported, otherwise
     * {@code false}.
     */
    public final boolean isAccessModeSupported(AccessMode accessMode) {
        return AccessMode.getMemberName(accessMode.ordinal(), vform) != null;
    }

    /**
     * Obtains a method handle bound to this VarHandle and the given access
     * mode.
     *
     * @apiNote This method, for a VarHandle {@code vh} and access mode
     * {@code {access-mode}}, returns a method handle that is equivalent to
     * method handle {@code bmh} in the following code (though it may be more
     * efficient):
     * <pre>{@code
     * MethodHandle mh = MethodHandles.varHandleExactInvoker(
     *                       vh.accessModeType(VarHandle.AccessMode.{access-mode}));
     *
     * MethodHandle bmh = mh.bindTo(vh);
     * }</pre>
     *
     * @param accessMode the access mode, corresponding to the
     * signature-polymorphic method of the same name
     * @return a method handle bound to this VarHandle and the given access mode
     */
    public final MethodHandle toMethodHandle(AccessMode accessMode) {
        MemberName mn = AccessMode.getMemberName(accessMode.ordinal(), vform);
        if (mn != null) {
            MethodHandle mh = getMethodHandle(accessMode.ordinal());
            return mh.bindTo(this);
        }
        else {
            // Ensure an UnsupportedOperationException is thrown
            return MethodHandles.varHandleInvoker(accessMode, accessModeType(accessMode)).
                    bindTo(this);
        }
    }

    @Stable
    TypesAndInvokers typesAndInvokers;

    static class TypesAndInvokers {
        final @Stable
        MethodType[] methodType_table =
                new MethodType[VarHandle.AccessType.values().length];

        final @Stable
        MethodHandle[] methodHandle_table =
                new MethodHandle[AccessMode.values().length];
    }

    @ForceInline
    private final TypesAndInvokers getTypesAndInvokers() {
        TypesAndInvokers tis = typesAndInvokers;
        if (tis == null) {
            tis = typesAndInvokers = new TypesAndInvokers();
        }
        return tis;
    }

    @ForceInline
    final MethodHandle getMethodHandle(int mode) {
        TypesAndInvokers tis = getTypesAndInvokers();
        MethodHandle mh = tis.methodHandle_table[mode];
        if (mh == null) {
            mh = tis.methodHandle_table[mode] = getMethodHandleUncached(mode);
        }
        return mh;
    }
    private final MethodHandle getMethodHandleUncached(int mode) {
        MethodType mt = accessModeType(AccessMode.values()[mode]).
                insertParameterTypes(0, VarHandle.class);
        MemberName mn = vform.getMemberName(mode);
        DirectMethodHandle dmh = DirectMethodHandle.make(mn);
        // Such a method handle must not be publically exposed directly
        // otherwise it can be cracked, it must be transformed or rebound
        // before exposure
        MethodHandle mh = dmh.copyWith(mt, dmh.form);
        assert mh.type().erase() == mn.getMethodType().erase();
        return mh;
    }


    /*non-public*/
    final void updateVarForm(VarForm newVForm) {
        if (vform == newVForm) return;
        UNSAFE.putObject(this, VFORM_OFFSET, newVForm);
        UNSAFE.fullFence();
    }

    static final BiFunction<String, List<Integer>, ArrayIndexOutOfBoundsException>
            AIOOBE_SUPPLIER = Preconditions.outOfBoundsExceptionFormatter(
            new Function<String, ArrayIndexOutOfBoundsException>() {
                @Override
                public ArrayIndexOutOfBoundsException apply(String s) {
                    return new ArrayIndexOutOfBoundsException(s);
                }
            });

    private static final long VFORM_OFFSET;

    static {
        try {
            VFORM_OFFSET = UNSAFE.objectFieldOffset(VarHandle.class.getDeclaredField("vform"));
        }
        catch (ReflectiveOperationException e) {
            throw newInternalError(e);
        }

        // The VarHandleGuards must be initialized to ensure correct
        // compilation of the guard methods
        UNSAFE.ensureClassInitialized(VarHandleGuards.class);
    }


    // Fence methods

    /**
     * Ensures that loads and stores before the fence will not be reordered
     * with
     * loads and stores after the fence.
     *
     * @apiNote Ignoring the many semantic differences from C and C++, this
     * method has memory ordering effects compatible with
     * {@code atomic_thread_fence(memory_order_seq_cst)}
     */
    @ForceInline
    public static void fullFence() {
        UNSAFE.fullFence();
    }

    /**
     * Ensures that loads before the fence will not be reordered with loads and
     * stores after the fence.
     *
     * @apiNote Ignoring the many semantic differences from C and C++, this
     * method has memory ordering effects compatible with
     * {@code atomic_thread_fence(memory_order_acquire)}
     */
    @ForceInline
    public static void acquireFence() {
        UNSAFE.loadFence();
    }

    /**
     * Ensures that loads and stores before the fence will not be
     * reordered with stores after the fence.
     *
     * @apiNote Ignoring the many semantic differences from C and C++, this
     * method has memory ordering effects compatible with
     * {@code atomic_thread_fence(memory_order_release)}
     */
    @ForceInline
    public static void releaseFence() {
        UNSAFE.storeFence();
    }

    /**
     * Ensures that loads before the fence will not be reordered with
     * loads after the fence.
     */
    @ForceInline
    public static void loadLoadFence() {
        UNSAFE.loadLoadFence();
    }

    /**
     * Ensures that stores before the fence will not be reordered with
     * stores after the fence.
     */
    @ForceInline
    public static void storeStoreFence() {
        UNSAFE.storeStoreFence();
    }
}
