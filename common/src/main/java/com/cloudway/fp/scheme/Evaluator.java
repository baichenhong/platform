/**
 * Cloudway Platform
 * Copyright (c) 2012-2013 Cloudway Technology, Inc.
 * All rights reserved.
 */

package com.cloudway.fp.scheme;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.cloudway.fp.$;
import com.cloudway.fp.control.ConditionCase;
import com.cloudway.fp.control.Trampoline;
import com.cloudway.fp.control.monad.trans.ContT;
import com.cloudway.fp.control.monad.trans.ExceptTC;
import com.cloudway.fp.data.Either;
import com.cloudway.fp.data.Fn;
import com.cloudway.fp.data.HashPMap;
import com.cloudway.fp.data.Maybe;
import com.cloudway.fp.data.MutablePMap;
import com.cloudway.fp.data.PMap;
import com.cloudway.fp.data.Ref;
import com.cloudway.fp.data.Seq;
import com.cloudway.fp.function.TriFunction;

import com.cloudway.fp.scheme.numsys.NumberPrimitives;

import static com.cloudway.fp.control.Conditionals.with;
import static com.cloudway.fp.control.Syntax.do_;
import static com.cloudway.fp.control.Syntax.let;
import static com.cloudway.fp.data.Either.left;
import static com.cloudway.fp.data.Either.right;
import static com.cloudway.fp.scheme.LispError.*;
import static com.cloudway.fp.scheme.LispVal.*;

// @formatter:off

@SuppressWarnings("Convert2MethodRef")
public class Evaluator extends ExceptTC<Evaluator, LispError, ContT<Trampoline.µ>> {
    private final SchemeParser parser = new SchemeParser();
    private final Env reportEnv;

    private static final Symbol INTERACTION_ENV = new Symbol("%INTERACTION-ENVIRONMENT%");
    private static final Symbol LOADED_MODULES = new Symbol("%LOADED-MODULES%");
    private static final Symbol STDLIB = new Symbol("stdlib");

    public Evaluator() {
        super(ContT.on(Trampoline.tclass));

        reportEnv = new Env();
        loadPrimitives(reportEnv, Primitives.class);
        loadPrimitives(reportEnv, NumberPrimitives.class);
        loadPrimitives(reportEnv, IOPrimitives.class);
        loadLib(reportEnv, STDLIB).getOrThrow(Fn.id());
    }

    public Either<LispError, LispVal> loadLib(Env env, Symbol module) {
        String name = module.name;
        if (!isModuleLoaded(env, module)) {
            try (InputStream is = getModuleResource(name + ".scm")) {
                if (is == null) {
                    return left(new LispError(name + ": no such module"));
                }

                Reader input = new InputStreamReader(is, StandardCharsets.UTF_8);
                Either<LispError, LispVal> res = run(env, parse(name, input));

                if (res.isLeft()) {
                    return res;
                } else {
                    addLoadedModule(env, module);
                }
            } catch (Exception ex) {
                return left(new LispError(ex));
            }
        }
        return right(VOID);
    }

    private static InputStream getModuleResource(String name) {
        return Primitives.class.getResourceAsStream("/META-INF/scheme/" + name);
    }

    private static boolean isModuleLoaded(Env env, Symbol module) {
        LispVal list = env.getSystem(LOADED_MODULES, Nil).get();
        while (list.isPair()) {
            Pair p = (Pair)list;
            if (module.equals(p.head))
                return true;
            list = p.tail;
        }
        return false;
    }

    private static void addLoadedModule(Env env, Symbol module) {
        Ref<LispVal> slot = env.getSystem(LOADED_MODULES, Nil);
        slot.set(Pair.cons(module, slot.get()));
    }

    public Env getNullEnv() {
        return new Env();
    }

    public Env getSchemeReportEnv() {
        Env env = reportEnv.extend();
        env.setSystem(INTERACTION_ENV, env);
        return env;
    }

    public Env getInteractionEnv(Env env) {
        return env.getSystem(INTERACTION_ENV, env).get();
    }

    public SchemeParser getParser() {
        return parser;
    }

    public Seq<Either<LispError, LispVal>> parse(String input) {
        return parser.parse(input);
    }

    public Seq<Either<LispError, LispVal>> parse(String name, Reader input) {
        return parser.parse(name, input);
    }

    public Either<LispError, LispVal> run(Env env, Seq<Either<LispError, LispVal>> exps) {
        Either<LispError, LispVal> res = right(VOID);
        while (!exps.isEmpty() && res.isRight()) {
            Either<LispError, LispVal> exp = exps.head();
            if (exp.isLeft()) {
                res = exp;
                break;
            } else {
                res = run(eval(env, exp.right()));
                exps = exps.tail();
            }
        }
        return res;
    }

    public <A> Either<LispError, A> run($<Evaluator, A> result) {
        return Trampoline.run(ContT.eval(runExcept(result)));
    }

    public Either<LispError, LispVal> evaluate(String form) {
        return run(getSchemeReportEnv(), parse(form));
    }

    public <A> $<Evaluator, A> throwE(Env env, LispError error) {
        return super.throwE(error.setCallTrace(env.getCallTrace()));
    }

    public <A> $<Evaluator, A> except(Env env, Either<LispError, A> m) {
        if (m.isLeft())
            m.left().setCallTrace(env.getCallTrace());
        return super.except(m);
    }

    // =======================================================================

    public $<Evaluator, Proc> analyze(Env ctx, LispVal form) {
        return with(form).<$<Evaluator, Proc>>get()
            .when(Datum  (this::analyzeDatum))
            .when(Symbol (this::analyzeVariable))
            .when(Cons   ((tag, args) -> analyzeList(ctx, form, tag, args)))
            .orElseGet(() -> throwE(ctx, new BadSpecialForm("unrecognized special form", form)));
    }

    public $<Evaluator, LispVal> eval(Env env, LispVal exp) {
        return bind(analyze(env, exp), proc -> proc.apply(env));
    }

    private $<Evaluator, Seq<LispVal>> evalM(Env env, Seq<Proc> procs) {
        return mapM(procs, proc -> proc.apply(env));
    }

    public $<Evaluator, LispVal> apply(Env env, LispVal func, LispVal args) {
        return with(func.normalize()).<$<Evaluator, LispVal>>get()
            .when(Prim    (p -> p.proc.apply(env.extend(env, p), null, args)))
            .when(Func    (f -> applyFunc(env, f, args)))
            .when(JClass  (c -> applyJObject(env, c, null, args)))
            .when(JObject (o -> applyJObject(env, o.getClass(), o, args)))
            .orElseGet(() -> throwE(env, new NotFunction("unrecognized function", func.show())));
    }

    private $<Evaluator, Proc> analyzeList(Env ctx, LispVal form, LispVal tag, LispVal args) {
        if (tag.isSymbol()) {
            switch (((Symbol)tag).name) {
            case "define":
                return with(args).<$<Evaluator, Proc>>get()
                  .when(List(id -> id.isSymbol()
                      ? analyzeVariableDefinition(ctx, (Symbol)id, VOID)
                      : badSyntax(ctx, "define", form)))
                  .when(List((first, exp) ->
                    first.isSymbol()
                      ? analyzeVariableDefinition(ctx, (Symbol)first, exp)
                      : analyzeFunctionDefinition(ctx, first, Pair.list(exp))))
                  .when(Cons((first, rest) ->
                      analyzeFunctionDefinition(ctx, first, rest)))
                  .orElseGet(() -> badSyntax(ctx, "define", form));

            case "define-values":
                return with(args).<$<Evaluator, Proc>>get()
                  .when(List((vars, exp) ->
                    vars.isList() && vars.allMatch(LispVal::isSymbol)
                      ? analyzeValuesDefinition(ctx, vars, exp)
                      : badSyntax(ctx, "define-values", form)))
                  .orElseGet(() -> badSyntax(ctx, "define-values", form));

            case "define-macro":
                return with(args).<$<Evaluator, Proc>>get()
                  .when(Cons((first, rest) -> analyzeMacroDefinition(ctx, first, rest)))
                  .orElseGet(() -> badSyntax(ctx, "define-macro", form));

            case "lambda":
                return with(args).<$<Evaluator, Proc>>get()
                  .when(Cons((first, rest) -> analyzeLambda(ctx, first, rest)))
                  .orElseGet(() -> badSyntax(ctx, "lambda", form));

            case "set!":
                return with(args).<$<Evaluator, Proc>>get()
                  .when(List((first, exp) ->
                    with(first).<$<Evaluator, Proc>>get()
                      .when(Symbol(id ->
                        analyzeAssignment(ctx, id, exp)))
                      .when(Cons((proc, pargs) ->
                        analyzeGetterWithSetter(ctx, proc, pargs, exp)))
                      .orElseGet(() -> badSyntax(ctx, "set!", args))))
                  .orElseGet(() -> badSyntax(ctx, "set!", args));

            case "if":
                return with(args).<$<Evaluator, Proc>>get()
                  .when(List((pred, conseq)      -> analyzeIf(ctx, pred, conseq, VOID)))
                  .when(List((pred, conseq, alt) -> analyzeIf(ctx, pred, conseq, alt)))
                  .orElseGet(() -> badSyntax(ctx, "if", form));

            case "cond":
                return analyzeCond(ctx, args);

            case "match":
                return with(args).<$<Evaluator, Proc>>get()
                  .when(Cons((exp, spec) -> analyzeMatch(ctx, exp, spec)))
                  .orElseGet(() -> badSyntax(ctx, "match", form));

            case "quote":
                return with(args).<$<Evaluator, Proc>>get()
                  .when(List(this::analyzeDatum))
                  .orElseGet(() -> badSyntax(ctx, "quote", form));

            case "quasiquote":
                return with(args).<$<Evaluator, Proc>>get()
                  .when(List(datum -> pure(env -> evalUnquote(env.incrementQL(), datum))))
                  .orElseGet(() -> badSyntax(ctx, "quasiquote", form));

            case "begin":
                return analyzeSequence(ctx, args);

            case "let":
                return args.isPair() && ((Pair)args).head.isSymbol()
                    ? analyzeLet(ctx, "let", ((Pair)args).tail, (ps, as, b) ->
                        translateNamedLet((Symbol)((Pair)args).head, ps, as, b))
                    : analyzeLet(ctx, "let", args, this::translateLet);

            case "let*":
                return analyzeLet(ctx, "let*", args, this::translateLetStar);

            case "letrec":
                return analyzeLet(ctx, "letrec", args, this::translateLetrec);

            case "letrec*":
                return analyzeLet(ctx, "letrec*", args, this::translateLetrecStar);

            default:
                Maybe<LispVal> var = ctx.lookupMacro((Symbol)tag);
                if (var.isPresent()) {
                    if (var.get() instanceof Macro) {
                        Macro mac = (Macro)var.get();
                        return expandMacro(ctx.extend(ctx, mac), mac, args);
                    } else if (var.get() instanceof PrimMacro) {
                        PrimMacro mac = (PrimMacro)var.get();
                        return mac.proc.apply(ctx.extend(ctx, mac), args);
                    }
                }
            }
        }

        return analyzeApplication(ctx, tag, args);
    }

    private $<Evaluator, Proc> analyzeDatum(LispVal datum) {
        $<Evaluator, LispVal> res = pure(datum);
        return pure(env -> res);
    }

    private $<Evaluator, Proc> analyzeVariable(Symbol var) {
        return pure(env -> env.lookup(var).either(v -> pure(v.get()), () -> unbound(env, var)));
    }

    private $<Evaluator, Proc> analyzeAssignment(Env ctx, Symbol var, LispVal exp) {
        return map(analyze(ctx, exp), vproc -> env -> setVar(env, var, vproc));
    }

    private $<Evaluator, Proc> analyzeGetterWithSetter(Env ctx, LispVal proc, LispVal args, LispVal exp) {
        // (set! (get-XXX args ...) exp)
        // ==> ((setter get-XXX) args ... exp)

        LispVal setter = Pair.list(getsym("setter"), proc);
        return do_(except(ctx, Pair.append(args, Pair.list(exp))), sargs ->
               analyze(ctx, Pair.cons(setter, sargs)));
    }

    @SuppressWarnings("RedundantTypeArguments")
    private $<Evaluator, LispVal> setVar(Env env, Symbol var, Proc vproc) {
        return env.lookup(var).<$<Evaluator, LispVal>>either(
            slot -> do_(vproc.apply(env), value ->
                    do_action(() -> slot.set(value))),
            () -> unbound(env, var));
    }

    private $<Evaluator, Proc> analyzeVariableDefinition(Env ctx, Symbol var, LispVal exp) {
        return map(analyze(ctx, exp), vproc ->
               env -> do_(vproc.apply(env), value ->
                      do_action(() -> env.put(var, value))));
    }

    private $<Evaluator, Proc> analyzeFunctionDefinition(Env ctx, LispVal first, LispVal body) {
        return with(first).<$<Evaluator, Proc>>get()
            .when(Cons((var, formals) ->
              var.isSymbol() && checkLambda(formals, body)
                ? map(analyzeSequence(ctx.extend(), body), bproc ->
                  env -> do_action(() -> env.put((Symbol)var, new Func(var.show(), formals, bproc, env))))
                : badSyntax(ctx, "define", first)))
            .orElseGet(() -> badSyntax(ctx, "define", first));
    }

    private $<Evaluator, Proc> analyzeMacroDefinition(Env ctx, LispVal first, LispVal body) {
        return with(first).<$<Evaluator, Proc>>get()
            .when(Cons((var, pattern) ->
              var.isSymbol() && checkMacro(pattern, body)
                ? do_(analyzeSequence(ctx.extend(), body), bproc ->
                  do_(action(() -> ctx.putMacro((Symbol)var, new Macro(var.show(), pattern, bproc))),
                  pure(env -> pure(VOID))))
                : badSyntax(ctx, "define-macro", first)))
            .orElseGet(() -> badSyntax(ctx, "define-macro", first));
    }

    private $<Evaluator, Proc> analyzeValuesDefinition(Env ctx, LispVal vars, LispVal exp) {
        return map(analyze(ctx, exp), vproc ->
               env -> do_(vproc.apply(env), result ->
                      result instanceof MultiVal
                        ? defineValues(env, vars, ((MultiVal)result).value)
                        : defineValues(env, vars, Pair.list(result))));
    }

    private $<Evaluator, LispVal> defineValues(Env env, LispVal vars, LispVal vals) {
        int nvars = 0;
        while (vars.isPair() && vals.isPair()) {
            Pair pn = (Pair)vars, pv = (Pair)vals;
            env.put((Symbol)pn.head, pv.head);
            vars = pn.tail;
            vals = pv.tail;
            nvars++;
        }

        if (!vars.isNil()) {
            while (vars.isPair()) {
                nvars++;
                vars = ((Pair)vars).tail;
            }
            return throwE(env, new NumArgs(nvars, vals));
        }

        if (!vals.isNil()) {
            return throwE(env, new NumArgs(nvars, vals));
        }

        return pure(VOID);
    }

    private $<Evaluator, Proc> analyzeLambda(Env ctx, LispVal formals, LispVal body) {
        if (checkLambda(formals, body)) {
            return map(analyzeSequence(ctx.extend(), body), bproc ->
                   env -> pure(new Func(formals, bproc, env)));
        } else {
            return badSyntax(ctx, "lambda", formals);
        }
    }

    private static boolean checkLambda(LispVal formals, LispVal body) {
        return formals.allMatch(LispVal::isSymbol) && body.isList() && !body.isNil();
    }

    private static boolean checkMacro(LispVal pattern, LispVal body) {
        return isPattern(pattern) && body.isList() && !body.isNil();
    }

    private $<Evaluator, Proc> analyzeApplication(Env ctx, LispVal operator, LispVal operands) {
        return do_(analyze(ctx, operator), fproc ->
               do_(operands.mapM(this, x -> analyze(ctx, x)), aprocs ->
               pure(env -> do_(fproc.apply(env), op ->
                           do_(aprocs.mapM(this, a -> ((Proc)a).apply(env)), args ->
                           apply(env, op, args))))));
    }

    private $<Evaluator, Proc> expandMacro(Env ctx, Macro macro, LispVal operands) {
        return match(ctx, macro.pattern, operands).<$<Evaluator, Proc>>either(
            err  -> throwE(ctx, err),
            eenv -> do_(macro.body.apply(eenv), mexp -> analyze(ctx, mexp)));
    }

    private $<Evaluator, LispVal> applyFunc(Env env, Func fn, LispVal args) {
        Env     eenv   = fn.closure.extend(env, fn);
        LispVal params = fn.params;
        int     nvars  = 0;

        while (params.isPair() && args.isPair()) {
            Pair   pp  = (Pair)params;
            Pair   pv  = (Pair)args;
            Symbol var = (Symbol)pp.head;

            eenv.put(var, pv.head);
            params = pp.tail;
            args   = pv.tail;
            nvars++;
        }

        if (params.isPair()) {
            // less arguments than parameters
            do {
                nvars++;
                params = ((Pair)params).tail;
            } while (params.isPair());
            return throwE(eenv, new NumArgs(nvars, args));
        }

        if (params.isNil() && !args.isNil()) {
            // more arguments than parameters
            return throwE(eenv, new NumArgs(nvars, args));
        }

        if (params.isSymbol()) {
            // varargs parameter
            eenv.put((Symbol)params, args);
        }

        return fn.body.apply(eenv);
    }

    public $<Evaluator, Proc> analyzeSequence(Env ctx, LispVal exps) {
        if (exps.isNil()) {
            return pure(env -> pure(VOID));
        }

        if (exps.isPair()) {
            Pair p = (Pair)exps;
            if (p.tail.isNil()) {
                return analyze(ctx, p.head);
            } else {
                return do_(analyze(ctx, p.head), first ->
                       do_(analyzeSequence(ctx, p.tail), rest ->
                       pure(env -> seqR(first.apply(env), () -> rest.apply(env)))));
            }
        }

        return badSyntax(ctx, "sequence", exps);
    }

    private $<Evaluator, Proc> analyzeIf(Env ctx, LispVal pred, LispVal conseq, LispVal alt) {
        return do_(analyze(ctx, pred),   pproc ->
               do_(analyze(ctx, conseq), cproc ->
               do_(analyze(ctx, alt),    aproc ->
               pure(env -> do_(pproc.apply(env), tval ->
                           tval.isTrue() ? cproc.apply(env)
                                         : aproc.apply(env))))));
    }

    // -----------------------------------------------------------------------

    private final Symbol ELSE = getsym("else");
    private final Symbol WHEN = getsym(":when");
    private final Proc   LAST = env -> pure(VOID);

    private $<Evaluator, Proc> analyzeCond(Env ctx, LispVal form) {
        return with(form).<$<Evaluator, Proc>>get()
            .when(List(last -> analyzeCondClause(ctx, last, LAST)))
            .when(Cons((hd, tl) ->
              bind(delay(() -> analyzeCond(ctx, tl)), rest ->
              analyzeCondClause(ctx, hd, rest))))
            .orElseGet(() -> badSyntax(ctx, "cond", form));
    }

    private $<Evaluator, Proc> analyzeCondClause(Env ctx, LispVal form, Proc rest) {
        return with(form).<$<Evaluator, Proc>>get()
            .when(List((test, mid, exp) ->
              mid.equals(getsym("=>"))
                ? analyzeRecipientCond(ctx, test, exp, rest)
                : analyzeNormalCond(ctx, test, Pair.list(mid, exp), rest)))
            .when(Cons((test, body) -> analyzeNormalCond(ctx, test, body, rest)))
            .orElseGet(() -> badSyntax(ctx, "cond", form));
    }

    private $<Evaluator, Proc> analyzeRecipientCond(Env ctx, LispVal test, LispVal exp, Proc rest) {
        return do_(analyze(ctx, test), tproc ->
               do_(analyze(ctx, exp),  pproc ->
               pure(env -> do_(tproc.apply(env), tval ->
                           tval.isTrue()
                             ? bind(pproc.apply(env), proc ->
                               apply(env, proc, Pair.list(tval)))
                             : rest.apply(env)))));
    }

    private $<Evaluator, Proc> analyzeNormalCond(Env ctx, LispVal test, LispVal body, Proc rest) {
        if (test.equals(ELSE)) {
            if (rest == LAST) {
                return analyzeSequence(ctx, body);
            } else {
                return badSyntax(ctx, "cond", Pair.cons(test, body));
            }
        }

        if (body.isNil()) {
            return map(analyze(ctx, test), tproc ->
                   env -> do_(tproc.apply(env), tval ->
                          tval.isTrue() ? pure(tval)
                                        : rest.apply(env)));
        } else {
            return bind(analyze(ctx, test), tproc ->
                   map(analyzeSequence(ctx, body), bproc ->
                   env -> do_(tproc.apply(env), tval ->
                          tval.isTrue() ? bproc.apply(env)
                                        : rest.apply(env))));
        }
    }

    // -----------------------------------------------------------------------

    private $<Evaluator, Proc> analyzeMatch(Env ctx, LispVal exp, LispVal spec) {
        return do_(analyze(ctx, exp), vproc ->
               do_(analyzeMatchClauses(ctx, spec), mproc ->
               pure(env -> do_(vproc.apply(env), val ->
                           mproc.apply(env, val.normalize())))));
    }

    private $<Evaluator, PProc> analyzeMatchClauses(Env ctx, LispVal form) {
        return with(form).<$<Evaluator, PProc>>get()
            .when(List(last -> analyzeMatchClause(ctx, last, null)))
            .when(Cons((hd, tl) ->
              bind(delay(() -> analyzeMatchClauses(ctx, tl)), rest ->
              analyzeMatchClause(ctx, hd, rest))))
            .orElseGet(() -> badSyntax(ctx, "match", form));
    }

    private $<Evaluator, PProc> analyzeMatchClause(Env ctx, LispVal form, PProc rest) {
        return with(form).<$<Evaluator, PProc>>get()
            .when(Cons((pat, body) ->
              pat.equals(ELSE)
                ? rest == null
                    ? analyzeSingleMatch(ctx, getsym("_"), body, rest)
                    : badSyntax(ctx, "match", form)

                : with(body).<$<Evaluator, PProc>>get()
                    .when(Cons((key, guard, exps) ->
                      key.equals(WHEN)
                        ? analyzeGuardedMatch(ctx, pat, guard, exps, rest)
                        : analyzeSingleMatch(ctx, pat, body, rest)))
                    .orElseGet(() ->
                          analyzeSingleMatch(ctx, pat, body, rest))))

            .orElseGet(() -> badSyntax(ctx, "match", form));
    }

    private $<Evaluator, PProc>
    analyzeSingleMatch(Env ctx, LispVal pattern, LispVal body, PProc rest) {
        return map(analyzeSequence(ctx, body), bproc -> (env, value) ->
               match(env, pattern, value).<$<Evaluator, LispVal>>either(
                 err  -> rest == null ? throwE(ctx, err) : rest.apply(env, value),
                 eenv -> bproc.apply(eenv)));
    }

    private $<Evaluator, PProc>
    analyzeGuardedMatch(Env ctx, LispVal pattern, LispVal guard, LispVal body, PProc rest) {
        return do_(analyze(ctx, guard), gproc ->
               do_(analyzeSequence(ctx, body), bproc ->
               pure((env, value) ->
                 match(env, pattern, value).<$<Evaluator, LispVal>>either(
                   err  -> rest == null ? throwE(ctx, err) : rest.apply(env, value),
                   eenv -> do_(gproc.apply(eenv), tval ->
                           tval.isTrue() ? bproc.apply(eenv) :
                           rest == null  ? throwE(eenv, new PatternMismatch(guard, value))
                                         : rest.apply(env, value))))));
    }

    // -----------------------------------------------------------------------

    private static boolean isPattern(LispVal val) {
        return with(val).<Boolean>get()
            .when(Text   (__ -> true))
            .when(Num    (__ -> true))
            .when(Bool   (__ -> true))
            .when(Symbol (__ -> true))
            .when(Nil    (() -> true))
            .when(Pair   (lst -> lst.allMatch(Evaluator::isPattern)))
            .orElse(false);
    }

    public static Either<LispError, Env> match(Env env, LispVal pattern, LispVal value) {
        return doMatch(pattern, value, HashPMap.empty()).map(bindings ->
               env.extend(bindings));
    }

    private static Either<LispError, PMap<Symbol, Ref<LispVal>>>
    doMatch(LispVal pattern, LispVal value, PMap<Symbol, Ref<LispVal>> bindings) {
        return with(pattern).<Either<LispError, PMap<Symbol, Ref<LispVal>>>>get()
            .when(Text   (__  -> matchConst(pattern, value, bindings)))
            .when(Num    (__  -> matchConst(pattern, value, bindings)))
            .when(Bool   (__  -> matchConst(pattern, value, bindings)))
            .when(Nil    (()  -> matchConst(pattern, value, bindings)))
            .when(Symbol (var -> matchVariable(var, value, bindings)))
            .when(Quoted (dat -> matchConst(dat, value, bindings)))
            .when(Pair   (lst -> matchList(lst, value, bindings)))
            .orElseGet(() -> left(new PatternMismatch(pattern, value)));
    }

    private static Either<LispError, PMap<Symbol, Ref<LispVal>>>
    matchConst(LispVal pattern, LispVal value, PMap<Symbol, Ref<LispVal>> bindings) {
        return pattern.equals(value)
            ? right(bindings)
            : left(new PatternMismatch(pattern, value));
    }

    private static Either<LispError, PMap<Symbol, Ref<LispVal>>>
    matchVariable(Symbol var, LispVal value, PMap<Symbol, Ref<LispVal>> bindings) {
        if ("_".equals(var.name)) {
            return right(bindings);
        }

        Maybe<Ref<LispVal>> bound_var = bindings.lookup(var);
        if (bound_var.isPresent()) {
            LispVal bound_val = bound_var.get().get();
            if (bound_val.equals(value)) {
                return right(bindings);
            } else {
                return left(new PatternMismatch(Pair.list(var, bound_val), value));
            }
        }

        return right(bindings.put(var, new Ref<>(value)));
    }

    private static Either<LispError, PMap<Symbol, Ref<LispVal>>>
    matchList(Pair pattern, LispVal value, PMap<Symbol, Ref<LispVal>> bindings) {
        if (value.isPair()) {
            Pair pv = (Pair)value;
            return doMatch(pattern.head, pv.head, bindings).flatMap(b ->
                   doMatch(pattern.tail, pv.tail, b));
        } else {
            return left(new PatternMismatch(pattern, value));
        }
    }

    // -----------------------------------------------------------------------

    private static final class LetParams {
        Seq<Symbol>  vars  = Seq.nil();
        Seq<LispVal> inits = Seq.nil();

        boolean add(LispVal var, LispVal init) {
            if (var.isSymbol()) {
                vars  = Seq.cons((Symbol)var, vars);
                inits = Seq.cons(init, inits);
                return true;
            } else {
                return false;
            }
        }
    }

    private $<Evaluator, Proc> analyzeLet(Env ctx, String name, LispVal form,
            TriFunction<Seq<Symbol>, Seq<Proc>, Proc, $<Evaluator, Proc>> trans) {
        return with(form).<$<Evaluator, Proc>>get()
            .when(Cons((params, body) ->
              do_(analyzeLetParams(ctx, name, params), lp ->
              do_(mapM(lp.inits, x -> analyze(ctx, x)), vprocs ->
              do_(analyzeSequence(ctx.extend(), body), bproc ->
              trans.apply(lp.vars, vprocs, bproc))))))
            .orElseGet(() -> badSyntax(ctx, name, form));
    }

    private $<Evaluator, LetParams> analyzeLetParams(Env ctx, String name, LispVal p) {
        LetParams lp = new LetParams();

        for (; p.isPair(); p = ((Pair)p).tail) {
            boolean ok = with(((Pair)p).head).<Boolean>get()
                .when(List((var      ) -> lp.add(var, VOID)))
                .when(List((var, init) -> lp.add(var, init)))
                .orElse(false);
            if (!ok) {
                return badSyntax(ctx, name, ((Pair)p).head);
            }
        }

        if (p.isNil()) {
            lp.vars  = lp.vars.reverse();
            lp.inits = lp.inits.reverse();
            return pure(lp);
        } else {
            return badSyntax(ctx, name, p);
        }
    }

    private static $<Evaluator, LispVal>
    extendEnv(Env env, Seq<Symbol> params, Seq<LispVal> args, Proc body) {
        Env eenv = env.extend();
        while (!params.isEmpty()) {
            eenv.put(params.head(), args.head());
            params = params.tail();
            args = args.tail();
        }
        return body.apply(eenv);
    }

    private $<Evaluator, LispVal> setVariables(Env env, Seq<Symbol> params, Seq<LispVal> args) {
        while (!params.isEmpty()) {
            env.lookup(params.head()).get().set(args.head());
            params = params.tail();
            args = args.tail();
        }
        return pure(VOID);
    }

    private $<Evaluator, Proc> translateLet(Seq<Symbol> params, Seq<Proc> inits, Proc body) {
        return pure(env -> do_(evalM(env, inits), args -> extendEnv(env, params, args, body)));
    }

    private $<Evaluator, Proc>
    translateNamedLet(Symbol tag, Seq<Symbol> params, Seq<Proc> inits, Proc body) {
        return pure(env ->
            do_(evalM(env, inits), args ->
            extendEnv(env, Seq.of(tag), Seq.of(VOID),
            eenv -> let(new Func(Pair.fromList(params), body, eenv), f ->
                    do_(setVar(eenv, tag, __ -> pure(f)),
                    do_(apply(eenv, f, Pair.fromList(args))))))));
    }

    private $<Evaluator, Proc> translateLetStar(Seq<Symbol> params, Seq<Proc> inits, Proc body) {
        if (params.isEmpty()) {
            return pure(env -> body.apply(env.extend()));
        } else {
            return map(translateLetStar(params.tail(), inits.tail(), body), rest ->
                   env -> do_(inits.head().apply(env), val ->
                          extendEnv(env, Seq.of(params.head()), Seq.of(val), rest)));
        }
    }

    private $<Evaluator, Proc> translateLetrec(Seq<Symbol> params, Seq<Proc> inits, Proc body) {
        return pure(env ->
            extendEnv(env, params, params.map(Fn.pure(VOID)),
            eenv -> do_(evalM(eenv, inits), args ->
                    do_(setVariables(eenv, params, args),
                    do_(body.apply(eenv))))));
    }

    private $<Evaluator, Proc> translateLetrecStar(Seq<Symbol> params, Seq<Proc> inits, Proc body) {
        return pure(env ->
            extendEnv(env, params, params.map(Fn.pure(VOID)),
            eenv -> do_(zipM_(params, inits, (n, v) -> setVar(eenv, n, v)),
                    do_(() -> body.apply(eenv.extend())))));
    }

    // -----------------------------------------------------------------------

    private final Symbol QQ = getsym("quasiquote");
    private final Symbol UNQ = getsym("unquote");
    private final Symbol UNQS = getsym("unquote-splicing");

    public $<Evaluator, LispVal> evalUnquote(Env env, LispVal exp) {
        return with(exp).<$<Evaluator, LispVal>>get()
            .when(Pair   (__ -> unquotePair(env, exp)))
            .when(Vector (v  -> unquoteVector(env, v)))
            .when(Datum  (__ -> pure(exp)))
            .when(Symbol (__ -> pure(exp)))
            .when(Nil    (() -> pure(exp)))
            .orElseGet(() -> throwE(env, new BadSpecialForm("unrecognized special form", exp)));
    }

    private $<Evaluator, LispVal> unquote(Env env, LispVal datum, boolean splicing) {
        env = env.decrementQL();
        return env.getQL() < 0  ? throwE(env, new BadSpecialForm("unquote: not in quasiquote", datum)) :
               env.getQL() == 0 ? eval(env, datum)
                                : map(evalUnquote(env, datum), x ->
                                  Pair.list(splicing ? UNQS : UNQ, x));
    }

    private $<Evaluator, LispVal> unquotePair(Env env, LispVal exp) {
        return with(exp).<$<Evaluator, LispVal>>get()
            .when(TaggedList(env, QQ, datum -> map(evalUnquote(env.incrementQL(), datum), x ->
                                               Pair.list(QQ, x))))

            .when(TaggedList(env, UNQ,  datum -> unquote(env, datum, false)))
            .when(TaggedList(env, UNQS, datum -> unquote(env, datum, true)))

            .when(Cons((hd, tl) -> unquotePair(env, hd, tl)))

            .orElseGet(() -> evalUnquote(env, exp));
    }

    private $<Evaluator, LispVal> unquotePair(Env env, LispVal hd, LispVal tl) {
        return with(hd).<$<Evaluator, LispVal>>get()
            .when(TaggedList(env, UNQS, datum ->
              do_(unquote(env, datum, true), xs ->
              do_(unquotePair(env, tl), ys ->
              except(env, Pair.append(xs, ys))))))

            .orElseGet(() ->
              do_(evalUnquote(env, hd), x ->
              do_(unquotePair(env, tl), ys ->
              pure(Pair.cons(x, ys)))));
    }

    private $<Evaluator, LispVal> unquoteVector(Env env, LispVal[] vec) {
        if (vec.length == 0)
            return pure(new Vec(vec));

        Pair pair = (Pair)Pair.fromList(Seq.of(vec));
        return do_(unquotePair(env, pair.head, pair.tail), r ->
               pure(vectorFromList(r)));
    }

    private static Vec vectorFromList(LispVal list) {
        ArrayList<LispVal> vec = new ArrayList<>();
        while (list.isPair()) {
            Pair p = (Pair)list;
            vec.add(p.head);
            list = p.tail;
        }
        return new Vec(vec.toArray(new LispVal[vec.size()]));
    }

    private <R> ConditionCase<LispVal, $<Evaluator, R>, RuntimeException>
    TaggedList(Env ctx, Symbol tag, Function<LispVal, $<Evaluator, R>> mapper) {
        return t -> {
            if (t.isPair()) {
                Pair p = (Pair)t;
                if (tag.equals(p.head) && p.tail.isPair()) {
                    Pair pp = (Pair)p.tail;
                    return pp.tail.isNil()
                        ? () -> mapper.apply(pp.head)
                        : () -> badSyntax(ctx, tag.name, t);
                }
            }
            return null;
        };
    }

    // -----------------------------------------------------------------------

    private static final Symbol DYNAMIC_WINDS = new Symbol("%DYNAMIC-WINDS%");

    private static Ref<Seq<Pair>> getDynamicWinds(Env env) {
        return env.getSystem(DYNAMIC_WINDS, Seq.<Pair>nil());
    }

    private static Seq<Pair> addDynamicWind(Env env, LispVal before, LispVal after) {
        Ref<Seq<Pair>> winds = getDynamicWinds(env);
        return winds.getAndSet(Seq.cons(Pair.cons(before, after), winds.get()));
    }

    public $<Evaluator, LispVal> callCC(Env env, LispVal proc) {
        Function<Function<LispVal, $<Evaluator, LispVal>>, $<Evaluator, LispVal>> f =
            k -> apply(env, proc, Pair.list(makeContProc(env, k)));

        return $(inner().<Either<LispError, LispVal>>callCC(c ->
            runExcept(f.apply(a -> $(c.escape(right(a)))))));
    }

    private Func makeContProc(Env env, Function<LispVal, $<Evaluator, LispVal>> k) {
        Seq<Pair> previous = getDynamicWinds(env).get();
        Symbol rid = env.newsym();

        return new Func(rid, eenv -> {
            Seq<Pair> current = getDynamicWinds(env).get();
            int n = current.length() - previous.length();
            return do_(dynamicUnwind(eenv, previous, n),
                   do_(k.apply(makeMultiVal(eenv.get(rid)))));
        }, env);
    }

    private $<Evaluator, LispVal> dynamicUnwind(Env env, Seq<Pair> previous, int n) {
        Ref<Seq<Pair>> winds = getDynamicWinds(env);
        Seq<Pair> current = winds.get();

        if (n == 0 || current == previous) {
            return pure(VOID);
        } else if (n < 0) {
            return guard(action(() -> winds.set(previous)),
                   guard(delay (() -> apply(env, previous.head().head, Nil)),
                         delay (() -> dynamicUnwind(env, previous.tail(), n + 1))));
        } else {
            return guard(delay(() -> dynamicUnwind(env, previous, n - 1)),
                   do_(action (() -> winds.set(current.tail())),
                   do_(delay  (() -> apply(env, current.head().tail, Nil)))));
        }
    }

    public $<Evaluator, LispVal> dynamicWind(Env env, LispVal before, LispVal thunk, LispVal after) {
        return do_(apply(env, before, Nil),
               do_(lazy(() -> addDynamicWind(env, before, after)), previous ->
               guard(delay(() -> {
                   getDynamicWinds(env).set(previous);
                   return apply(env, after, Nil);
               }), apply(env, thunk, Nil))));
    }

    public $<Evaluator, LispVal> values(LispVal args) {
        return pure(makeMultiVal(args));
    }

    public $<Evaluator, LispVal> callWithValues(Env env, LispVal producer, LispVal consumer) {
        return do_(apply(env, producer, Nil), result ->
               result instanceof MultiVal
                 ? apply(env, consumer, ((MultiVal)result).value)
                 : apply(env, consumer, Pair.list(result)));
    }

    private static LispVal makeMultiVal(LispVal args) {
        if (args.isPair() && ((Pair)args).tail.isNil()) {
            return ((Pair)args).head;
        } else {
            return new MultiVal(args);
        }
    }

    public $<Evaluator, LispVal> reset(Env env, Proc proc) {
        return $(inner().reset(runExcept(proc.apply(env))));
    }

    public $<Evaluator, LispVal> shift(Env env, Symbol id, Proc proc) {
        Function<Function<LispVal, $<Evaluator, LispVal>>, $<Evaluator, LispVal>> f =
            k -> extendEnv(env, Seq.of(id), Seq.of(makeShiftProc(env, k)), proc);

        return $(inner().<Either<LispError,LispVal>, Either<LispError,LispVal>>shift(c ->
            runExcept(f.apply(a -> $(inner().lift(c.apply(right(a))))))));
    }

    private static Func makeShiftProc(Env env, Function<LispVal, $<Evaluator, LispVal>> k) {
        Symbol cid = env.newsym();
        return new Func(Pair.list(cid), eenv -> k.apply(eenv.get(cid)), env);
    }

    // -----------------------------------------------------------------------

    private <T> $<Evaluator, T> badSyntax(Env env, String name, LispVal val) {
        return throwE(env, new BadSpecialForm(name + ": bad syntax", val));
    }

    private $<Evaluator, LispVal> unbound(Env env, Symbol var) {
        return throwE(env, new UnboundVar(var.show()));
    }

    private $<Evaluator, LispVal> do_action(Runnable action) {
        return seqR(action(action), pure(VOID));
    }

    public Symbol getsym(String name) {
        return parser.getsym(name);
    }

    public KeySym getkeysym(String name) {
        return parser.getkeysym(name);
    }

    // =======================================================================

    public void loadPrimitives(Env env, Class<?> primLib) {
        for (Method method : primLib.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                LispVal prim = loadPrimitive(method);
                if (prim instanceof PrimMacro) {
                    env.putMacro(getsym(((PrimMacro)prim).name), prim);
                } else {
                    env.put(getsym(((Prim)prim).name), prim);
                }
            }
        }
    }

    public LispVal loadPrimitive(Method method) {
        String name = getPrimName(method);
        if (method.isAnnotationPresent(Syntax.class)) {
            return new PrimMacro(name, makeMacroDispatcher(method));
        } else {
            return new Prim(name, makePrimDispatcher(method));
        }
    }

    private static String getPrimName(Method method) {
        Name nameTag = method.getAnnotation(Name.class);
        return nameTag != null ? nameTag.value() : method.getName().replace('_', '-');
    }

    @SuppressWarnings("unchecked")
    private BiFunction<Env, LispVal, $<Evaluator, Proc>>
    makeMacroDispatcher(Method method) {
        if (!checkMacroMethod(method)) {
            throw new LispError("Invalid syntax method: " + method);
        }

        return (env, args) -> {
            try {
                return ($<Evaluator, Proc>)method.invoke(null, this, env, args);
            } catch (InvocationTargetException ex) {
                return throwE(env, new LispError(ex.getTargetException()));
            } catch (Exception ex) {
                return throwE(env, new LispError(ex));
            }
        };
    }

    private TriFunction<Env, Object, LispVal, $<Evaluator, LispVal>>
    makePrimDispatcher(Method method) {
        if (!checkPrimMethod(method)) {
            throw new LispError("Invalid primitive method: " + method);
        }

        return makeMethodDispatcher(method);
    }

    private TriFunction<Env, Object, LispVal, $<Evaluator, LispVal>>
    makeMethodDispatcher(Method method) {
        BiFunction<Env, LispVal, $<Evaluator, Object[]>>
            unpacker = getArgumentsUnpacker(method);
        Packer packer = Packer.get(method.getReturnType());

        method.setAccessible(true);

        return (env, target, args) ->
            do_(unpacker.apply(env, args), jargs ->
            do_(invoke(env, method, target, jargs), result ->
            do_(packResult(env, packer, method.getReturnType(), result))));
    }

    private BiFunction<Env, LispVal, $<Evaluator, LispVal>>
    makeConstructorDispatcher(Constructor<?> cons) {
        BiFunction<Env, LispVal, $<Evaluator, Object[]>>
            unpacker = getArgumentsUnpacker(cons);

        cons.setAccessible(true);

        return (env, args) ->
            do_(unpacker.apply(env, args), jargs ->
            do_(newInstance(env, cons, jargs)));
    }

    private static final Symbol JCLASS_DECLS = new Symbol("%JCLASS_DECLS*");

    private JClassDecl getJClassDecl(Env env, Class<?> cls) {
        MutablePMap<Class<?>, JClassDecl> decls
            = env.getSystem(JCLASS_DECLS, () ->
                new MutablePMap<Class<?>, JClassDecl>(HashPMap.empty())).get();
        return decls.computeIfAbsent(cls, this::createJClassDecl);
    }

    private JClassDecl createJClassDecl(Class<?> cls) {
        JClassDecl.Builder builder = new JClassDecl.Builder();

        for (Constructor<?> cons : cls.getConstructors()) {
            builder.addConstructor(cons, makeConstructorDispatcher(cons));
        }

        for (Method method : cls.getMethods()) {
            if (checkPrimMethod(method)) {
                builder.addMethod(getPrimName(method), method, makeMethodDispatcher(method));
            }
        }

        return builder.build();
    }

    private $<Evaluator, LispVal> applyJObject(Env env, Class<?> cls, Object obj, LispVal args) {
        if (!args.isPair()) {
            return throwE(env, new TypeMismatch("list", args));
        }

        Pair p = (Pair)args;
        if (!(p.head instanceof Symbol)) {
            return throwE(env, new TypeMismatch("keyword", p.head));
        }

        try {
        String name = ((Symbol)p.head).name;
            if (obj == null && "new".equals(name)) {
                return invokeConstructor(env, cls, p.tail);
            } else {
                return invokeMethod(env, cls, name, obj, p.tail);
            }
        } catch (LispError ex) {
            return throwE(env, ex);
        } catch (Exception ex) {
            return throwE(env, new LispError(ex));
        }
    }

    private $<Evaluator, LispVal> invokeConstructor(Env env, Class<?> cls, LispVal args)
        throws IllegalAccessException, InstantiationException
    {
        if (args.isNil()) {
            return pure(new JObject(cls.newInstance()));
        }

        Maybe<BiFunction<Env, LispVal, $<Evaluator, LispVal>>>
            dispatcher = getJClassDecl(env, cls).getConstructorDispatcher(args);

        if (dispatcher.isAbsent()) {
            return throwE(env, new LispError(cls.getName() + ": constructor not found or argument type don't match"));
        }

        return dispatcher.get().apply(env, args);
    }

    private $<Evaluator, LispVal>
    invokeMethod(Env env, Class<?> cls, String name, Object obj, LispVal args) {
        Maybe<TriFunction<Env, Object, LispVal, $<Evaluator, LispVal>>>
            dispatcher = getJClassDecl(env, cls).getMethodDispatcher(obj == null, name, args);

        if (dispatcher.isAbsent()) {
            return throwE(env, new LispError(name + ": method not found or argument type don't match"));
        }

        return dispatcher.get().apply(env, obj, args);
    }

    private static boolean checkPrimMethod(Method method) {
        if (method.getReturnType() == $.class) {
            return checkGenericReturnType(method, $.class, Evaluator.class, LispVal.class);
        } else if (method.getReturnType() == Either.class) {
            return checkGenericReturnType(method, Either.class, LispError.class, LispVal.class);
        } else {
            return true;
        }
    }

    private static boolean checkMacroMethod(Method method) {
        Class<?>[] params = method.getParameterTypes();
        return params.length == 3
            && params[0] == Evaluator.class
            && params[1] == Env.class
            && params[2] == LispVal.class
            && checkGenericReturnType(method, $.class, Evaluator.class, Proc.class);
    }

    private static boolean checkGenericReturnType(Method method, Class<?> rawType, Class<?>... args) {
        Type rt = method.getGenericReturnType();
        if (!(rt instanceof ParameterizedType))
            return false;

        ParameterizedType ret = (ParameterizedType)rt;
        Type[] targs = ret.getActualTypeArguments();
        return ret.getRawType() == rawType && Arrays.equals(targs, args);
    }

    private BiFunction<Env, LispVal, $<Evaluator, Object[]>> getArgumentsUnpacker(Executable method) {
        Class<?>[] params  = method.getParameterTypes();
        boolean    passMe  = false;
        boolean    passEnv = false;
        int        nparams = params.length;
        Unpacker[] required, optional;
        Unpacker   varargs;
        int        i       = 0;

        if (nparams > 0 && params[i] == Evaluator.class) {
            passMe = true;
            nparams--;
            i++;
        }

        if (nparams > 0 && params[i] == Env.class) {
            passEnv = true;
            nparams--;
            i++;
        }

        varargs  = getVarArgUnpacker(method);
        optional = getOptionalUnpackers(method, varargs != null, params);

        if (varargs != null)
            nparams--;
        nparams -= optional.length;

        required = new Unpacker[nparams];
        for (int j = 0; j < nparams; i++, j++) {
            required[j] = Unpacker.get(params[i]);
        }

        return unpackArgs(params, passMe, passEnv, required, optional, varargs);
    }

    private static final Unpacker[] NO_OPTIONALS = new Unpacker[0];

    private static Unpacker[]
    getOptionalUnpackers(Executable method, boolean varargs, Class<?>[] actual_types) {
        Type[]      params   = method.getGenericParameterTypes();
        int         nparams  = params.length - (varargs ? 1 : 0);
        int         iopt     = -1;
        Unpacker[]  optional = NO_OPTIONALS;

        for (int i = 0; i < nparams; i++) {
            Class<?> type = getOptionalType(params[i]);
            if (type != null) {
                if (iopt == -1) {
                    optional = new Unpacker[nparams - i];
                    iopt = 0;
                }
                optional[iopt++] = Unpacker.get(type);
                actual_types[i] = type;
            } else {
                if (iopt != -1) {
                    throw new LispError("Optional arguments must be contiguous: " + method);
                }
            }
        }

        return optional;
    }

    private static Class<?> getOptionalType(Type param) {
        if (param instanceof ParameterizedType) {
            ParameterizedType t = (ParameterizedType)param;
            Type[] as = t.getActualTypeArguments();
            if (t.getRawType() == Maybe.class && as.length == 1 && as[0] instanceof Class) {
                return (Class<?>)as[0];
            }
        }
        return null;
    }

    private static Unpacker getVarArgUnpacker(Executable method) {
        Class<?>[] params = method.getParameterTypes();
        int nparams = params.length;

        if (method.isAnnotationPresent(VarArgs.class)) {
            if (nparams == 0 || params[nparams - 1] != LispVal.class)
                throw new LispError("The last argument of a VarArgs method must be a LispVal");

            return (type, args) -> Either.right(args);
        }

        if (method.isVarArgs()) {
            assert nparams > 0 && params[nparams - 1].isArray();
            Class<?> va_type = params[nparams - 1].getComponentType();
            Unpacker va_unpacker = Unpacker.get(va_type);

            return (type, val) -> {
                int nargs = 0;
                for (LispVal p = val; p.isPair(); p = ((Pair)p).tail)
                    nargs++;

                Object args = Array.newInstance(va_type, nargs);
                Either<LispError, Object> v;

                for (int i = 0; i < nargs; i++) {
                    Pair p = (Pair)val;
                    v = va_unpacker.apply(va_type, p.head);
                    if (v.isLeft())
                        return v;
                    Array.set(args, i, v.right());
                    val = p.tail;
                }

                return val.isNil() ? right(args) : left(new TypeMismatch("pair", val));
            };
        }

        return null;
    }

    private BiFunction<Env, LispVal, $<Evaluator, Object[]>>
    unpackArgs(Class<?>[] params, boolean passMe, boolean passEnv,
               Unpacker[] required, Unpacker[] optional, Unpacker varargs) {
        return (env, args) -> {
            Object[] res = new Object[params.length];
            int nreq = required.length;
            int nopt = optional.length;
            int i = 0;
            int j;
            Either<LispError, Object> v;

            // normalize arguments
            for (LispVal x = args; x.isPair(); ) {
                Pair p = (Pair)x;
                p.head = p.head.normalize();
                x = p.tail;
            }

            if (passMe)
                res[i++] = this;
            if (passEnv)
                res[i++] = env;

            for (j = 0; j < nreq && args.isPair(); i++, j++) {
                Pair p = (Pair)args;
                v = required[j].apply(params[i], p.head);
                if (v.isLeft())
                    return throwE(env, v.left());
                res[i] = v.right();
                args = p.tail;
            }

            if (j < nreq)
                return throwE(env, new NumArgs(nreq, args));

            for (j = 0; j < nopt; i++, j++) {
                if (args.isNil()) {
                    res[i] = Maybe.empty();
                } else if (args.isPair()) {
                    Pair p = (Pair)args;
                    v = optional[j].apply(params[i], p.head);
                    if (v.isLeft())
                        return throwE(env, v.left());
                    res[i] = Maybe.of(v.right());
                    args = p.tail;
                } else {
                    return throwE(env, new TypeMismatch("pair", args));
                }
            }

            if (varargs != null) {
                v = varargs.apply(params[i], args);
                if (v.isLeft())
                    return throwE(env, v.left());
                res[i] = v.right();
            } else if (!args.isNil()) {
                return throwE(env, new NumArgs(nreq + nopt, args));
            }

            return pure(res);
        };
    }

    @SuppressWarnings("unchecked")
    private $<Evaluator, LispVal> packResult(Env env, Packer packer, Class<?> type, Object obj) {
        if (obj == null)
            return pure(VOID);

        if (type == $.class)
            return ($<Evaluator, LispVal>)obj;

        if (type == Either.class)
            return except(env, (Either<LispError, LispVal>)obj);

        return pure(packer.apply(obj));
    }


    private $<Evaluator, Object> invoke(Env env, Method method, Object target, Object[] args) {
        try {
            return pure(method.invoke(target, args));
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getTargetException();
            if (cause instanceof LispError)
                return throwE(env, (LispError)cause);
            return throwE(env, new LispError(cause));
        } catch (Exception ex) {
            return throwE(env, new LispError(ex));
        }
    }

    private $<Evaluator, LispVal> newInstance(Env env, Constructor<?> cons, Object[] args) {
        try {
            return pure(new JObject(cons.newInstance(args)));
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getTargetException();
            if (cause instanceof LispError)
                return throwE(env, (LispError)cause);
            return throwE(env, new LispError(cause));
        } catch (Exception ex) {
            return throwE(env, new LispError(ex));
        }
    }
}
