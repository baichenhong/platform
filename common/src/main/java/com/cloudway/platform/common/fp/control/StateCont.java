/**
 * Cloudway Platform
 * Copyright (c) 2012-2013 Cloudway Technology, Inc.
 * All rights reserved.
 */

package com.cloudway.platform.common.fp.control;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import com.cloudway.platform.common.fp.data.Fn;
import com.cloudway.platform.common.fp.data.Seq;
import com.cloudway.platform.common.fp.data.Unit;
import com.cloudway.platform.common.fp.function.TriFunction;

/**
 * A stateful CPS computation.
 *
 * @param <A> the type of intermediate result of computation
 * @param <S> the type of state used during computation
 *
 * @see Cont
 */
public final class StateCont<A, S> {
    // the CPS transfer type
    @FunctionalInterface
    private interface K<A, S, R> {
        MonadState<R, S> apply(Function<A, MonadState<R, S>> f);
    }

    // the CPS transfer function
    private final K<A, S, ?> kf;

    // construct a continuation monad
    private <R> StateCont(K<A, S, R> f) {
        this.kf = f;
    }

    // helper method to construct continuation from a transfer function
    private static <A, S, R> StateCont<A, S> $(K<A, S, R> f) {
        return new StateCont<>(f);
    }

    /**
     * Run the CPS computation to get final result.
     *
     * @param k the function that accept computation value and return final result
     * @return the final result of computation
     */
    @SuppressWarnings("unchecked")
    public <R> MonadState<R, S> run(Function<A, MonadState<R, S>> k) {
        return ((K<A,S,R>)kf).apply(k);
    }

    /**
     * The result of running a CPS computation with 'return' as the final
     * continuation.
     */
    public MonadState<A, S> eval() {
        return run(MonadState::<A,S>pure);
    }

    /**
     * Yield a pure computation that results in the given value.
     *
     * @param a the pure value of the computation result
     * @return the continuation that yield the pure value
     */
    public static <A, S> StateCont<A, S> pure(A a) {
        return $(f -> f.apply(a));
    }

    /**
     * Synonym of {@link #pure(Object) pure}.
     */
    public static <A, S> StateCont<A, S> yield(A a) {
        return pure(a);
    }

    /**
     * Yield a thunk that has a lazily evaluated computation.
     *
     * @param a a thunk that eventually produce computation result
     * @return the continuation that yield the computation.
     */
    public static <A, S> StateCont<A, S> lazy(Supplier<A> a) {
        Supplier<A> t = Fn.lazy(a);
        return $(f -> f.apply(t.get()));
    }

    /**
     * Yield an action computation that has no result.
     */
    public static <S> StateCont<Unit, S> action(Runnable a) {
        return $(f -> { a.run(); return f.apply(Unit.U); });
    }

    /**
     * Transfer a continuation by feeding the intermediate value to the given
     * function and yield the result of function application.
     *
     * @param f the function that accept a intermediate value and compute
     *          a new value
     * @return the continuation that yield the result of function application
     */
    public <B> StateCont<B, S> map(Function<? super A, ? extends B> f) {
        return $(c -> run(c.compose(f)));
    }

    /**
     * Transfer a continuation by feeding the intermediate value to the given
     * function.
     *
     * @param k the function that accept a intermediate value and transfer
     *          to a new continuation
     * @return the new continuation applying the transfer function
     */
    public <B> StateCont<B, S> bind(Function<? super A, StateCont<B, S>> k) {
        return $(c -> run(a -> k.apply(a).run(c)));
    }

    /**
     * Transfer a continuation by discarding the intermediate value.
     *
     * @param b the new continuation transformation
     * @return a continuation that transfers this continuation to the given continuation
     */
    public <B> StateCont<B, S> andThen(StateCont<B, S> b) {
        return $(c -> run(a -> b.run(c)));
    }

    /**
     * Transfer a continuation by discarding the intermediate value.
     *
     * @param b the new continuation transformation
     * @return a continuation that transfers this continuation to the given continuation
     */
    public <B> StateCont<B, S> andThen(Supplier<StateCont<B, S>> b) {
        return $(c -> run(a -> b.get().run(c)));
    }

    /**
     * Apply a function to transform the result of a computation.
     *
     * @param f the function that transform the result of computation
     * @return a continuation applying the transfer function
     */
    public <R> StateCont<A, S> mapCont(Function<MonadState<R, S>, MonadState<R, S>> f) {
        return $((Function<A, MonadState<R, S>> c) -> f.apply(run(c)));
    }

    /**
     * Apply a function to transform the continuation passed to a CPS
     * computation.
     *
     * @param f the transfer function
     * @return a continuation applying the transfer function
     */
    public <B, R> StateCont<B, S>
    withCont(Function<Function<B, MonadState<R, S>>, Function<A, MonadState<R, S>>> f) {
        return $((Function<B, MonadState<R, S>> c) -> run(f.apply(c)));
    }

    /**
     * Fetch the current value of the state within the monad.
     */
    public static <S> StateCont<S, S> get() {
        return lift(MonadState.get());
    }

    /**
     * Sets the state within the monad.
     */
    public static <S> StateCont<Unit, S> put(S s) {
        return lift(MonadState.put(s));
    }

    /**
     * Updates the state to the result of applying a function to the current state.
     */
    public static <S> StateCont<Unit, S> modify(Function<S, S> f) {
        return lift(MonadState.modify(f));
    }

    /**
     * The exit function type.
     */
    @FunctionalInterface
    public interface Exit<A, S> {
        /**
         * Escape from CPS computation with a value.
         *
         * @param value the escaped value
         */
        StateCont<?, S> escape(A value);

        /**
         * A convenient method to escape from CPS computation when the given
         * condition satisfied.
         *
         * @param test the condition to test
         * @param value the escaped value
         */
        default StateCont<?, S> escapeIf(boolean test, A value) {
            return test ? escape(value) : pure(Unit.U);
        }
    }

    /**
     * {@code callCC (call-with-current-continuation)} calls its argument function,
     * passing it the current continuation.  It provides an escape continuation
     * mechanism for use with continuation monads.  Escape continuations one allow
     * to abort the current computation and return a value immediately. They achieve
     * a similar effect to 'try-catch-throw' control flow.  The advantage of this
     * function over calling 'return' is that it makes the continuation explicit,
     * allowing more flexibility and better control.
     *
     * <p>The standard idiom used with {@code callCC} is to provide a lambda-expression
     * to name the continuation. Then calling the named continuation anywhere within
     * its scope will escape from the computation, even if it is many layers deep
     * within nested computations</p>
     *
     * @param f the function that passing the current continuation
     * @return a continuation that may or may not escaped from current continuation
     */
    public static <A, S> StateCont<A, S> callCC(Function<Exit<A, S>, StateCont<A, S>> f) {
        return $(c -> f.apply(a -> $(__ -> c.apply(a))).run(c));
    }

    /**
     * Prompt a state computation to a stateful CPS computation.
     *
     * @param m the state computation
     * @return the prompted stateful CPS computation
     */
    public static <A, S> StateCont<A, S> lift(MonadState<A, S> m) {
        return $(m::bind);
    }

    /**
     * Delimits the continuation of any {@link #shift(Function) shift} inside current
     * continuation.
     */
    public static <A, S> StateCont<A, S> reset(StateCont<A, S> m) {
        return lift(m.eval());
    }

    /**
     * Captures the continuation up to the nearest enclosing {@link #reset(StateCont) reset}
     * and passes it to the given function.
     */
    public static <A, R, S> StateCont<A, S> shift(Function<Function<A, MonadState<R, S>>, StateCont<R, S>> f) {
        return $((Function<A, MonadState<R, S>> k) -> f.apply(k).eval());
    }

    /**
     * Evaluate each action in the sequence from left to right, and collect
     * the result.
     */
    public static <A, S> StateCont<Seq<A>, S> flatM(Seq<StateCont<A, S>> ms) {
        return ms.foldRightStrict(pure(Seq.nil()), liftM2(Seq::cons));
    }

    /**
     * Evaluate each action in the sequence from left to right, and ignore
     * the result.
     */
    public static <A, S> StateCont<Unit, S> sequence(Seq<StateCont<A, S>> ms) {
        return ms.foldRight(pure(Unit.U), StateCont::andThen);
    }

    /**
     * The {@code mapM} is analogous to {@link Seq#map(Function) map} except that
     * its result is encapsulated in a {@code Continuation}.
     */
    public static <A, B, S> StateCont<Seq<B>, S>
    mapM(Seq<A> xs, Function<? super A, StateCont<B, S>> f) {
        return flatM(xs.map(f));
    }

    /**
     * {@code mapM_} is equivalent to {@code sequence(xs.map(f))}.
     */
    public static <A, B, S> StateCont<Unit, S>
    mapM_(Seq<A> xs, Function<? super A, StateCont<B, S>> f) {
        return sequence(xs.map(f));
    }

    /**
     * This generalizes the list-based filter function.
     */
    public static <A, S> StateCont<Seq<A>, S>
    filterM(Seq<A> xs, Function<? super A, StateCont<Boolean, S>> p) {
        return xs.isEmpty()
               ? pure(Seq.nil())
               : p.apply(xs.head()).bind(flg ->
                 filterM(xs.tail(), p).bind(ys ->
                 pure(flg ? Seq.cons(xs.head(), ys) : ys)));
    }

    /**
     * The {@code foldM} is analogous to {@link Seq#foldLeft(Object,BiFunction) foldLeft},
     * except that its result is encapsulated in a {@code Continuation}. Note that
     * {@code foldM} works from left-to-right over the lists arguments. If right-to-left
     * evaluation is required, the input list should be reversed.
     */
    public static <A, B, S> StateCont<B, S>
    foldM(B r0, Seq<A> xs, BiFunction<B, ? super A, StateCont<B, S>> f) {
        return xs.foldLeft(pure(r0), (m, x) -> m.bind(r -> f.apply(r, x)));
    }

    /**
     * Kleisli composition of monads.
     */
    public static <A, B, C, S> Function<A, StateCont<C, S>>
    compose(Function<A, StateCont<B, S>> f, Function<B, StateCont<C, S>> g) {
        return x -> f.apply(x).bind(g);
    }

    /**
     * Promote a function to a CPS computation function.
     */
    public static <A, B, S> Function<StateCont<A, S>, StateCont<B, S>>
    liftM(Function<? super A, ? extends B> f) {
        return m -> m.map(f);
    }

    /**
     * Promote a function to a CPS computation function.
     */
    public static <A, B, C, S> BiFunction<StateCont<A, S>, StateCont<B, S>, StateCont<C, S>>
    liftM2(BiFunction<? super A, ? super B, ? extends C> f) {
        return (m1, m2) -> m1.bind(x1 -> m2.map(x2 -> f.apply(x1, x2)));
    }

    /**
     * Promote a function to a CPS computation function.
     */
    public static <A, B, C, D, S> TriFunction<StateCont<A, S>, StateCont<B, S>, StateCont<C, S>, StateCont<D, S>>
    liftM3(TriFunction<? super A, ? super B, ? super C, ? extends D> f) {
        return (m1, m2, m3) -> m1.bind(x1 -> m2.bind(x2 -> m3.map(x3 -> f.apply(x1, x2, x3))));
    }

    /**
     * Bind the given function to the given CPS computations with a final join.
     */
    public static <A, B, C, S> StateCont<C, S>
    zip(StateCont<A, S> ma, StateCont<B, S> mb, BiFunction<? super A, ? super B, ? extends C> f) {
        return StateCont.<A,B,C,S>liftM2(f).apply(ma, mb);
    }

    /**
     * Bind the given function to the given CPS computations with a final join.
     */
    public static <A, B, C, D, S> StateCont<D, S>
    zip3(StateCont<A, S> ma, StateCont<B, S> mb, StateCont<C, S> mc,
         TriFunction<? super A, ? super B, ? super C, ? extends D> f) {
        return StateCont.<A,B,C,D,S>liftM3(f).apply(ma, mb, mc);
    }
}