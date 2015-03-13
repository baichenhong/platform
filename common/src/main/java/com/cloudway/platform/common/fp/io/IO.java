/**
 * Cloudway Platform
 * Copyright (c) 2012-2013 Cloudway Technology, Inc.
 * All rights reserved.
 */

package com.cloudway.platform.common.fp.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.cloudway.platform.common.fp.control.Conditionals;
import com.cloudway.platform.common.fp.data.Seq;
import com.cloudway.platform.common.fp.data.Unit;

/**
 * A value of type IO is a computation which, when performed, does some I/O
 * before returning a value.
 *
 * <p>{@code IO} is a monad, so IO actions can be combined using either the
 * comprehension or the {@link IO#bind(Function)} operations from the Monad
 * class.</p>
 *
 * @param <A> the type of computation value
 */
@FunctionalInterface
public interface IO<A> {
    /**
     * Run the I/O action.
     *
     * @return the result of I/O action
     * @throws IOException if I/O error occurred
     */
    A runIO() throws IOException;

    /**
     * Run the I/O action, wraps IOException to an UncheckedIOException.
     *
     * @return the result of I/O action
     * @throws UncheckedIOException if I/O error occurred
     */
    default A runUncheckedIO() throws UncheckedIOException {
        try {
            return runIO();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Wraps a pure value to an I/O action.
     *
     * @param a the pure value
     * @return an I/O action that wrapping the pure value
     */
    static <A> IO<A> pure(A a) {
        return () -> a;
    }

    /**
     * The do nothing I/O action.
     */
    IO<Unit> unit = pure(Unit.U);

    /**
     * Maps the I/O action result with the given function.
     *
     * @param f a function that maps the I/O action result
     * @return a new I/O action that return the result of mapping
     */
    default <B> IO<B> map(Function<? super A, ? extends B> f) {
        return () -> f.apply(runIO());
    }

    /**
     * Binds the I/O action result with the given function.
     *
     * @param f a function that bind the I/O action result
     * @return a new I/O action that returns the result of binding
     */
    default <B> IO<B> bind(Function<? super A, ? extends IO<B>> f) {
        return () -> f.apply(runIO()).runIO();
    }

    /**
     * Sequentially perform I/O actions.
     *
     * @param other the another I/O action that performed after this I/O action
     * @return a new I/O action that sequentially perform I/O actions
     */
    default <B> IO<B> andThen(IO<B> other) {
        return () -> { runIO(); return other.runIO(); };
    }

    /**
     * Sequentially perform I/O actions.
     *
     * @param other the another I/O action that performed after this I/O action
     * @return a new I/O action that sequentially perform I/O actions
     */
    default <B> IO<B> andThen(Supplier<IO<B>> other) {
        return () -> { runIO(); return other.get().runIO(); };
    }

    /**
     * Evaluate each action in the sequence from left to right, and collect
     * the result.
     */
    static <A> IO<Seq<A>> sequence(Seq<IO<A>> ms) {
        return ms.foldRightStrict(pure(Seq.nil()), liftM2(Seq::cons));
    }

    /**
     * Evaluate each action in the sequence from left to right, and discard
     * the result.
     */
    static IO<Unit> sequence_(Seq<IO<?>> ms) {
        return ms.foldRight(pure(Unit.U), IO::andThen);
    }

    /**
     * The {@code mapM} is analogous to {@link Seq#map(Function) map} except that
     * its result is encapsulated in an {@code IO}.
     */
    static <A, B> IO<Seq<B>> mapM(Seq<A> xs, Function<? super A, IO<B>> f) {
        return sequence(xs.map(f));
    }

    /**
     * {@code mapM_} is equivalent to {@code sequence_(xs.map(f))}.
     */
    static <A> IO<Unit> mapM_(Seq<A> xs, Function<? super A, IO<?>> f) {
        return sequence_(xs.map(f));
    }

    /**
     * This generalizes the list-based filter function.
     */
    static <A> IO<Seq<A>> filterM(Seq<A> xs, Function<? super A, IO<Boolean>> p) {
        return xs.isEmpty()
               ? pure(Seq.nil())
               : p.apply(xs.head()).bind(flg ->
                 filterM(xs.tail(), p).bind(ys ->
                 pure(flg ? Seq.cons(xs.head(), ys) : ys)));
    }

    /**
     * The {@code foldM} is analogous to {@link Seq#foldLeft(Object,BiFunction) foldLeft},
     * except that its result is encapsulated in a {@code IO}. Note that {@code foldM}
     * works from left-to-right over the lists arguments, If right-to-left evaluation
     * is required, the input list should be reversed.
     */
    static <A, B> IO<B> foldM(B r0, Seq<A> xs, BiFunction<B, ? super A, IO<B>> f) {
        return xs.foldLeft(pure(r0), (m, x) -> m.bind(r -> f.apply(r, x)));
    }

    /**
     * Kleisli composition of monads.
     */
    static <A, B, C> Function<A, IO<C>> compose(Function<A, IO<B>> f, Function<B, IO<C>> g) {
        return x -> f.apply(x).bind(g);
    }

    /**
     * Promote a function to an I/O action.
     */
    static <A, B> Function<IO<A>, IO<B>> liftM(Function<? super A, ? extends B> f) {
        return m -> m.map(f);
    }

    /**
     * Promote a function to an I/O action.
     */
    static <A, B, C> BiFunction<IO<A>, IO<B>, IO<C>> liftM2(BiFunction<? super A, ? super B, ? extends C> f) {
        return (m1, m2) -> m1.bind(x1 -> m2.map(x2 -> f.apply(x1, x2)));
    }

    // The naive methods that work around Java lambda exception handling

    static <T> T run(IOSupplier<? extends T> action) throws IOException {
        try {
            return action.produce();
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    static void run_(IOAction action) throws IOException {
        try {
            action.perform();
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    static <T> void forEach(Stream<T> stream, IOConsumer<? super T> action)
        throws IOException
    {
        try {
            stream.forEach(IOConsumer.wrap(action));
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    static <T> void forEach(Iterable<T> collection, IOConsumer<? super T> action)
        throws IOException
    {
        try {
            collection.forEach(IOConsumer.wrap(action));
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    static <K, V> void forEach(Map<K, V> map, IOBiConsumer<? super K, ? super V> action)
        throws IOException
    {
        try {
            map.forEach(IOBiConsumer.wrap(action));
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    static Conditionals.ActionBrancher<IOException> with() {
        return Conditionals.actionBrancher();
    }

    static <T> Conditionals.ActionSwitcher<T, IOException> with(T value) {
        return Conditionals.actionSwitcher(value);
    }

    static <T, U> Conditionals.BiActionSwitcher<T, U, IOException> with(T t, U u) {
        return Conditionals.biActionSwitcher(t, u);
    }
}