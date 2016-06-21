// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.query2;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.collect.CompactHashSet;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.graph.Digraph;
import com.google.devtools.build.lib.packages.BuildFileContainsErrorsException;
import com.google.devtools.build.lib.packages.DependencyFilter;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.pkgcache.TargetPatternEvaluator;
import com.google.devtools.build.lib.profiler.AutoProfiler;
import com.google.devtools.build.lib.query2.engine.AllRdepsFunction;
import com.google.devtools.build.lib.query2.engine.Callback;
import com.google.devtools.build.lib.query2.engine.FunctionExpression;
import com.google.devtools.build.lib.query2.engine.QueryEvalResult;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QueryExpressionMapper;
import com.google.devtools.build.lib.query2.engine.QueryUtil.AbstractUniquifier;
import com.google.devtools.build.lib.query2.engine.RdepsFunction;
import com.google.devtools.build.lib.query2.engine.TargetLiteral;
import com.google.devtools.build.lib.query2.engine.Uniquifier;
import com.google.devtools.build.lib.skyframe.BlacklistedPackagePrefixesValue;
import com.google.devtools.build.lib.skyframe.ContainingPackageLookupFunction;
import com.google.devtools.build.lib.skyframe.FileValue;
import com.google.devtools.build.lib.skyframe.GraphBackedRecursivePackageProvider;
import com.google.devtools.build.lib.skyframe.PackageLookupValue;
import com.google.devtools.build.lib.skyframe.PackageValue;
import com.google.devtools.build.lib.skyframe.PrepareDepsOfPatternsFunction;
import com.google.devtools.build.lib.skyframe.RecursivePackageProviderBackedTargetPatternResolver;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.TargetPatternValue;
import com.google.devtools.build.lib.skyframe.TargetPatternValue.TargetPatternKey;
import com.google.devtools.build.lib.skyframe.TransitiveTraversalValue;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.WalkableGraph;
import com.google.devtools.build.skyframe.WalkableGraph.WalkableGraphFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import javax.annotation.Nullable;

/**
 * {@link AbstractBlazeQueryEnvironment} that introspects the Skyframe graph to find forward and
 * reverse edges. Results obtained by calling {@link #evaluateQuery} are not guaranteed to be in
 * any particular order. As well, this class eagerly loads the full transitive closure of targets,
 * even if the full closure isn't needed.
 */
public class SkyQueryEnvironment extends AbstractBlazeQueryEnvironment<Target> {
  // 10k is likely a good balance between using batch efficiently and not blowing up memory.
  // TODO(janakr): Unify with RecursivePackageProviderBackedTargetPatternResolver's constant.
  private static final int BATCH_CALLBACK_SIZE = 10000;

  protected WalkableGraph graph;
  private Supplier<ImmutableSet<PathFragment>> blacklistPatternsSupplier;

  private final BlazeTargetAccessor accessor = new BlazeTargetAccessor(this);
  private final int loadingPhaseThreads;
  private final WalkableGraphFactory graphFactory;
  private final List<String> universeScope;
  private final String parserPrefix;
  private final PathPackageLocator pkgPath;

  private static final Logger LOG = Logger.getLogger(SkyQueryEnvironment.class.getName());

  private static final Function<Target, Label> TARGET_LABEL_FUNCTION =
      new Function<Target, Label>() {
    
    @Override
    public Label apply(Target target) {
      return target.getLabel();
    }
  };

  private final ListeningExecutorService threadPool =
      MoreExecutors.listeningDecorator(
          Executors.newFixedThreadPool(
              Runtime.getRuntime().availableProcessors(),
              new ThreadFactoryBuilder().setNameFormat("GetPackages-%d").build()));
  private RecursivePackageProviderBackedTargetPatternResolver resolver;

  private static class BlacklistSupplier implements Supplier<ImmutableSet<PathFragment>> {
    private final WalkableGraph graph;

    BlacklistSupplier(WalkableGraph graph) {
      this.graph = graph;
    }

    @Override
    public ImmutableSet<PathFragment> get() {
      return ((BlacklistedPackagePrefixesValue)
              graph.getValue(BlacklistedPackagePrefixesValue.key()))
          .getPatterns();
    }
  }

  public SkyQueryEnvironment(
      boolean keepGoing,
      int loadingPhaseThreads,
      EventHandler eventHandler,
      Set<Setting> settings,
      Iterable<QueryFunction> extraFunctions, String parserPrefix,
      WalkableGraphFactory graphFactory,
      List<String> universeScope, PathPackageLocator pkgPath) {
    super(
        keepGoing,
        /*strictScope=*/ true,
        /*labelFilter=*/ Rule.ALL_LABELS,
        eventHandler,
        settings,
        extraFunctions);
    this.loadingPhaseThreads = loadingPhaseThreads;
    this.graphFactory = graphFactory;
    this.pkgPath = pkgPath;
    this.universeScope = Preconditions.checkNotNull(universeScope);
    this.parserPrefix = parserPrefix;
    Preconditions.checkState(!universeScope.isEmpty(),
        "No queries can be performed with an empty universe");
  }

  private void init() throws InterruptedException {
    EvaluationResult<SkyValue> result;
    try (AutoProfiler p = AutoProfiler.logged("evaluation and walkable graph", LOG)) {
      result = graphFactory.prepareAndGet(universeScope, parserPrefix, loadingPhaseThreads,
          eventHandler);
    }
    graph = result.getWalkableGraph();

    blacklistPatternsSupplier = Suppliers.memoize(new BlacklistSupplier(graph));

    SkyKey universeKey = graphFactory.getUniverseKey(universeScope, parserPrefix);
    ImmutableList<TargetPatternKey> universeTargetPatternKeys =
        PrepareDepsOfPatternsFunction.getTargetPatternKeys(
            PrepareDepsOfPatternsFunction.getSkyKeys(universeKey, eventHandler));
    GraphBackedRecursivePackageProvider graphBackedRecursivePackageProvider =
        new GraphBackedRecursivePackageProvider(graph, universeTargetPatternKeys, pkgPath);
    resolver =
        new RecursivePackageProviderBackedTargetPatternResolver(
            graphBackedRecursivePackageProvider,
            eventHandler,
            TargetPatternEvaluator.DEFAULT_FILTERING_POLICY,
            threadPool);

    // The prepareAndGet call above evaluates a single PrepareDepsOfPatterns SkyKey.
    // We expect to see either a single successfully evaluated value or a cycle in the result.
    Collection<SkyValue> values = result.values();
    if (!values.isEmpty()) {
      Preconditions.checkState(values.size() == 1, "Universe query \"%s\" returned multiple"
              + " values unexpectedly (%s values in result)", universeScope, values.size());
      Preconditions.checkNotNull(result.get(universeKey), result);
    } else {
      // No values in the result, so there must be an error. We expect the error to be a cycle.
      boolean foundCycle = !Iterables.isEmpty(result.getError().getCycleInfo());
      Preconditions.checkState(foundCycle, "Universe query \"%s\" failed with non-cycle error: %s",
          universeScope, result.getError());
    }
  }

  @Override
  public QueryExpression transformParsedQuery(QueryExpression queryExpression) {
    // Transform each occurrence of an expressions of the form 'rdeps(<universeScope>, <T>)' to
    // 'allrdeps(<T>)'. The latter is more efficient.
    if (universeScope.size() != 1) {
      return queryExpression;
    }
    final TargetPattern.Parser targetPatternParser = new TargetPattern.Parser(parserPrefix);
    String universeScopePattern = Iterables.getOnlyElement(universeScope);
    final String absoluteUniverseScopePattern =
        targetPatternParser.absolutize(universeScopePattern);
    QueryExpressionMapper rdepsToAllRDepsMapper = new QueryExpressionMapper() {
      @Override
      public QueryExpression map(FunctionExpression functionExpression) {
        if (functionExpression.getFunction().getName().equals(new RdepsFunction().getName())) {
          List<Argument> args = functionExpression.getArgs();
          QueryExpression universeExpression = args.get(0).getExpression();
          if (universeExpression instanceof TargetLiteral) {
            TargetLiteral literalUniverseExpression = (TargetLiteral) universeExpression;
            String absolutizedUniverseExpression =
                targetPatternParser.absolutize(literalUniverseExpression.getPattern());
            if (absolutizedUniverseExpression.equals(absoluteUniverseScopePattern)) {
              List<Argument> argsTail = args.subList(1, functionExpression.getArgs().size());
              return new FunctionExpression(new AllRdepsFunction(), argsTail);
            }
          }
        }
        return super.map(functionExpression);
      }
    };
    QueryExpression transformedQueryExpression = queryExpression.getMapped(rdepsToAllRDepsMapper);
    LOG.info(String.format("transformed query [%s] to [%s]", queryExpression,
        transformedQueryExpression));
    return transformedQueryExpression;
  }

  @Override
  public QueryEvalResult evaluateQuery(QueryExpression expr, Callback<Target> callback)
      throws QueryException, InterruptedException {
    // Some errors are reported as QueryExceptions and others as ERROR events (if --keep_going). The
    // result is set to have an error iff there were errors emitted during the query, so we reset
    // errors here.
    eventHandler.resetErrors();
    init();

    // SkyQueryEnvironment batches callback invocations using a BatchStreamedCallback, created here
    // so that there's one per top-level evaluateQuery call. The batch size is large enough that
    // per-call costs of calling the original callback are amortized over a good number of targets,
    // and small enough that holding a batch of targets in memory doesn't risk an OOM error.
    //
    // This flushes the batched callback prior to constructing the QueryEvalResult in the unlikely
    // case of a race between the original callback and the eventHandler.
    final BatchStreamedCallback aggregator =
        new BatchStreamedCallback(callback, BATCH_CALLBACK_SIZE, createUniquifier());

    final AtomicBoolean empty = new AtomicBoolean(true);
    try (final AutoProfiler p = AutoProfiler.logged("evaluating query", LOG)) {
      try {
        expr.eval(
            this,
            new Callback<Target>() {
              @Override
              public void process(Iterable<Target> partialResult)
                  throws QueryException, InterruptedException {
                empty.compareAndSet(true, Iterables.isEmpty(partialResult));
                aggregator.process(partialResult);
              }
            });
      } catch (QueryException e) {
        throw new QueryException(e, expr);
      }
      aggregator.processLastPending();
    }

    if (eventHandler.hasErrors()) {
      if (!keepGoing) {
        // This case represents loading-phase errors reported during evaluation
        // of target patterns that don't cause evaluation to fail per se.
        throw new QueryException("Evaluation of query \"" + expr
            + "\" failed due to BUILD file errors");
      } else {
        eventHandler.handle(Event.warn("--keep_going specified, ignoring errors.  "
            + "Results may be inaccurate"));
      }
    }

    return new QueryEvalResult(!eventHandler.hasErrors(), empty.get());
  }

  private Map<Target, Collection<Target>> makeTargetsMap(Map<SkyKey, Iterable<SkyKey>> input) {
    ImmutableMap.Builder<Target, Collection<Target>> result = ImmutableMap.builder();
    
    Map<SkyKey, Target> allTargets =
        makeTargetsFromSkyKeys(Sets.newHashSet(Iterables.concat(input.values())));

    for (Map.Entry<SkyKey, Target> entry : makeTargetsFromSkyKeys(input.keySet()).entrySet()) {
      Iterable<SkyKey> skyKeys = input.get(entry.getKey());
      Set<Target> targets = CompactHashSet.createWithExpectedSize(Iterables.size(skyKeys));
      for (SkyKey key : skyKeys) {
        Target target = allTargets.get(key);
        if (target != null) {
          targets.add(target);
        }
      }
      result.put(entry.getValue(), targets);
    }
    return result.build();
  }

  private Map<Target, Collection<Target>> getRawFwdDeps(Iterable<Target> targets) {
    return makeTargetsMap(graph.getDirectDeps(makeTransitiveTraversalKeys(targets)));
  }

  private Map<Target, Collection<Target>> getRawReverseDeps(Iterable<Target> targets) {
    return makeTargetsMap(graph.getReverseDeps(makeTransitiveTraversalKeys(targets)));
  }

  private Set<Label> getAllowedDeps(Rule rule) {
    Set<Label> allowedLabels = new HashSet<>(rule.getTransitions(dependencyFilter).values());
    allowedLabels.addAll(rule.getVisibility().getDependencyLabels());
    // We should add deps from aspects, otherwise they are going to be filtered out.
    allowedLabels.addAll(rule.getAspectLabelsSuperset(dependencyFilter));
    return allowedLabels;
  }

  private Collection<Target> filterFwdDeps(Target target, Collection<Target> rawFwdDeps) {
    if (!(target instanceof Rule)) {
      return rawFwdDeps;
    }
    final Set<Label> allowedLabels = getAllowedDeps((Rule) target);
    return Collections2.filter(rawFwdDeps,
        new Predicate<Target>() {
          @Override
          public boolean apply(Target target) {
            return allowedLabels.contains(target.getLabel());
          }
        });
  }

  /** Targets may not be in the graph because they are not in the universe or depend on cycles. */
  private void warnIfMissingTargets(
      Iterable<Target> targets, Set<Target> result) {
    if (Iterables.size(targets) != result.size()) {
      Set<Target> missingTargets = Sets.difference(ImmutableSet.copyOf(targets), result);
      eventHandler.handle(Event.warn("Targets were missing from graph: " + missingTargets));
    }
  }

  @Override
  public Collection<Target> getFwdDeps(Iterable<Target> targets) {
    Set<Target> result = new HashSet<>();
    Map<Target, Collection<Target>> rawFwdDeps = getRawFwdDeps(targets);
    warnIfMissingTargets(targets, rawFwdDeps.keySet());
    for (Map.Entry<Target, Collection<Target>> entry : rawFwdDeps.entrySet()) {
      result.addAll(filterFwdDeps(entry.getKey(), entry.getValue()));
    }
    return result;
  }

  @Override
  public Collection<Target> getReverseDeps(Iterable<Target> targets) {
    Set<Target> result = CompactHashSet.create();
    Map<Target, Collection<Target>> rawReverseDeps = getRawReverseDeps(targets);
    warnIfMissingTargets(targets, rawReverseDeps.keySet());

    CompactHashSet<Target> visited = CompactHashSet.create();

    Set<Label> keys = CompactHashSet.create(Collections2.transform(rawReverseDeps.keySet(),
        TARGET_LABEL_FUNCTION));
    for (Collection<Target> parentCollection : rawReverseDeps.values()) {
      for (Target parent : parentCollection) {
        if (visited.add(parent)) {
          if (parent instanceof Rule && dependencyFilter != DependencyFilter.ALL_DEPS) {
            for (Label label : getAllowedDeps((Rule) parent)) {
              if (keys.contains(label)) {
                result.add(parent);
              }
            }
          } else {
            result.add(parent);
          }
        }
      }
    }
    return result;
  }

  @Override
  public Set<Target> getTransitiveClosure(Set<Target> targets) {
    Set<Target> visited = new HashSet<>();
    Collection<Target> current = targets;
    while (!current.isEmpty()) {
      Collection<Target> toVisit = Collections2.filter(current,
          Predicates.not(Predicates.in(visited)));
      current = getFwdDeps(toVisit);
      visited.addAll(toVisit);
    }
    return ImmutableSet.copyOf(visited);
  }

  // Implemented with a breadth-first search.
  @Override
  public Set<Target> getNodesOnPath(Target from, Target to) {
    // Tree of nodes visited so far.
    Map<Target, Target> nodeToParent = new HashMap<>();
    // Contains all nodes left to visit in a (LIFO) stack.
    Deque<Target> toVisit = new ArrayDeque<>();
    toVisit.add(from);
    nodeToParent.put(from, null);
    while (!toVisit.isEmpty()) {
      Target current = toVisit.removeFirst();
      if (to.equals(current)) {
        return ImmutableSet.copyOf(Digraph.getPathToTreeNode(nodeToParent, to));
      }
      for (Target dep : getFwdDeps(ImmutableList.of(current))) {
        if (!nodeToParent.containsKey(dep)) {
          nodeToParent.put(dep, current);
          toVisit.addFirst(dep);
        }
      }
    }
    // Note that the only current caller of this method checks first to see if there is a path
    // before calling this method. It is not clear what the return value should be here.
    return null;
  }

  @Override
  public void eval(QueryExpression expr, Callback<Target> callback)
      throws QueryException, InterruptedException {
    expr.eval(this, callback);
  }

  private static Uniquifier<Target> uniquifier() {
    return new AbstractUniquifier<Target, Label>() {
      @Override
      protected Label extractKey(Target target) {
        return target.getLabel();
      }
    };
  }

  @Override
  public Uniquifier<Target> createUniquifier() {
    return uniquifier();
  }

  @Override
  public void getTargetsMatchingPattern(
      QueryExpression owner, String pattern, Callback<Target> callback) throws QueryException {
    // Directly evaluate the target pattern, making use of packages in the graph.
    try {
      TargetPatternKey targetPatternKey =
          ((TargetPatternKey)
              TargetPatternValue.key(
                      pattern, TargetPatternEvaluator.DEFAULT_FILTERING_POLICY, parserPrefix)
                  .argument());
      TargetPattern parsedPattern = targetPatternKey.getParsedPattern();
      ImmutableSet<PathFragment> subdirectoriesToExclude =
          targetPatternKey.getAllSubdirectoriesToExclude(blacklistPatternsSupplier);
      parsedPattern.eval(resolver, subdirectoriesToExclude, callback, QueryException.class);
    } catch (TargetParsingException e) {
      reportBuildFileError(owner, e.getMessage());
    } catch (InterruptedException e) {
      throw new QueryException(owner, e.getMessage());
    }
  }

  @Override
  public Set<Target> getBuildFiles(
      QueryExpression caller,
      Set<Target> nodes,
      boolean buildFiles,
      boolean subincludes,
      boolean loads)
      throws QueryException {
    Set<Target> dependentFiles = new LinkedHashSet<>();
    Set<Package> seenPackages = new HashSet<>();
    // Keep track of seen labels, to avoid adding a fake subinclude label that also exists as a
    // real target.
    Set<Label> seenLabels = new HashSet<>();

    // Adds all the package definition files (BUILD files and build
    // extensions) for package "pkg", to "buildfiles".
    for (Target x : nodes) {
      Package pkg = x.getPackage();
      if (seenPackages.add(pkg)) {
        if (buildFiles) {
          addIfUniqueLabel(pkg.getBuildFile(), seenLabels, dependentFiles);
        }

        List<Label> extensions = new ArrayList<>();
        if (subincludes) {
          extensions.addAll(pkg.getSubincludeLabels());
        }
        if (loads) {
          extensions.addAll(pkg.getSkylarkFileDependencies());
        }

        for (Label subinclude : extensions) {
          addIfUniqueLabel(getSubincludeTarget(subinclude, pkg), seenLabels, dependentFiles);

          if (buildFiles) {
            // Also add the BUILD file of the subinclude.
            try {
              addIfUniqueLabel(
                  getSubincludeTarget(subinclude.getLocalTargetLabel("BUILD"), pkg),
                  seenLabels,
                  dependentFiles);

            } catch (LabelSyntaxException e) {
              throw new AssertionError("BUILD should always parse as a target name", e);
            }
          }
        }
      }
    }
    return dependentFiles;
  }

  private static void addIfUniqueLabel(Target node, Set<Label> labels, Set<Target> nodes) {
    if (labels.add(node.getLabel())) {
      nodes.add(node);
    }
  }

  private static Target getSubincludeTarget(Label label, Package pkg) {
    return new FakeSubincludeTarget(label, pkg);
  }

  @Override
  public TargetAccessor<Target> getAccessor() {
    return accessor;
  }

  @Override
  public Target getTarget(Label label) throws TargetNotFoundException, QueryException {
    SkyKey packageKey = PackageValue.key(label.getPackageIdentifier());
    if (!graph.exists(packageKey)) {
      throw new QueryException(packageKey + " does not exist in graph");
    }
    try {
      PackageValue packageValue = (PackageValue) graph.getValue(packageKey);
      if (packageValue != null) {
        Package pkg = packageValue.getPackage();
        if (pkg.containsErrors()) {
          throw new BuildFileContainsErrorsException(label.getPackageIdentifier());
        }
        return packageValue.getPackage().getTarget(label.getName());
      } else {
        throw (NoSuchThingException) Preconditions.checkNotNull(
            graph.getException(packageKey), label);
      }
    } catch (NoSuchThingException e) {
      throw new TargetNotFoundException(e);
    }
  }

  @Override
  public void buildTransitiveClosure(QueryExpression caller, Set<Target> targets, int maxDepth)
      throws QueryException {
    // Everything has already been loaded, so here we just check for errors so that we can
    // pre-emptively throw/report if needed.
    Iterable<SkyKey> transitiveTraversalKeys = makeTransitiveTraversalKeys(targets);
    ImmutableList.Builder<String> errorMessagesBuilder = ImmutableList.builder();

    // First, look for errors in the successfully evaluated TransitiveTraversalValues. They may
    // have encountered errors that they were able to recover from.
    Set<Entry<SkyKey, SkyValue>> successfulEntries =
        graph.getSuccessfulValues(transitiveTraversalKeys).entrySet();
    Builder<SkyKey> successfulKeysBuilder = ImmutableSet.builder();
    for (Entry<SkyKey, SkyValue> successfulEntry : successfulEntries) {
      successfulKeysBuilder.add(successfulEntry.getKey());
      TransitiveTraversalValue value = (TransitiveTraversalValue) successfulEntry.getValue();
      String firstErrorMessage = value.getFirstErrorMessage();
      if (firstErrorMessage != null) {
        errorMessagesBuilder.add(firstErrorMessage);
      }
    }
    ImmutableSet<SkyKey> successfulKeys = successfulKeysBuilder.build();

    // Next, look for errors from the unsuccessfully evaluated TransitiveTraversal skyfunctions.
    Iterable<SkyKey> unsuccessfulKeys =
        Iterables.filter(transitiveTraversalKeys, Predicates.not(Predicates.in(successfulKeys)));
    Set<Entry<SkyKey, Exception>> errorEntries =
        graph.getMissingAndExceptions(unsuccessfulKeys).entrySet();
    for (Map.Entry<SkyKey, Exception> entry : errorEntries) {
      if (entry.getValue() == null) {
        // Targets may be in the graph because they are not in the universe or depend on cycles.
        eventHandler.handle(Event.warn(entry.getKey().argument() + " does not exist in graph"));
      } else {
        errorMessagesBuilder.add(entry.getValue().getMessage());
      }
    }

    // Lastly, report all found errors.
    ImmutableList<String> errorMessages = errorMessagesBuilder.build();
    for (String errorMessage : errorMessages) {
      reportBuildFileError(caller, errorMessage);
    }
  }

  @Override
  protected void preloadOrThrow(QueryExpression caller, Collection<String> patterns)
      throws QueryException, TargetParsingException {
    // SkyQueryEnvironment directly evaluates target patterns in #getTarget and similar methods
    // using its graph, which is prepopulated using the universeScope (see #init), so no
    // preloading of target patterns is necessary.
  }

  private static final Function<SkyKey, Label> SKYKEY_TO_LABEL = new Function<SkyKey, Label>() {
    @Nullable
    @Override
    public Label apply(SkyKey skyKey) {
      SkyFunctionName functionName = skyKey.functionName();
      if (!functionName.equals(SkyFunctions.TRANSITIVE_TRAVERSAL)) {
        // Skip non-targets.
        return null;
      }
      return (Label) skyKey.argument();
    }
  };

  private Map<SkyKey, Target> makeTargetsFromSkyKeys(Iterable<SkyKey> keys) {
    Multimap<SkyKey, SkyKey> packageKeyToTargetKeyMap = ArrayListMultimap.create();
    for (SkyKey key : keys) {
      Label label = SKYKEY_TO_LABEL.apply(key);
      if (label == null) {
        continue;
      }
      packageKeyToTargetKeyMap.put(PackageValue.key(label.getPackageIdentifier()), key);
    }
    ImmutableMap.Builder<SkyKey, Target> result = ImmutableMap.builder();
    Map<SkyKey, SkyValue> packageMap = graph.getSuccessfulValues(packageKeyToTargetKeyMap.keySet());
    for (Map.Entry<SkyKey, SkyValue> entry : packageMap.entrySet()) {
      for (SkyKey targetKey : packageKeyToTargetKeyMap.get(entry.getKey())) {
        try {
          result.put(
              targetKey,
              ((PackageValue) entry.getValue())
                  .getPackage()
                  .getTarget((SKYKEY_TO_LABEL.apply(targetKey)).getName()));
        } catch (NoSuchTargetException e) {
          // Skip missing target.
        }
      }
    }
    return result.build();
  }

  private static final Function<Target, SkyKey> TARGET_TO_SKY_KEY =
      new Function<Target, SkyKey>() {
        @Override
        public SkyKey apply(Target target) {
          return TransitiveTraversalValue.key(target.getLabel());
        }
      };

  private static Iterable<SkyKey> makeTransitiveTraversalKeys(Iterable<Target> targets) {
    return Iterables.transform(targets, TARGET_TO_SKY_KEY);
  }

  @Override
  public Target getOrCreate(Target target) {
    return target;
  }

  /**
   * Returns package lookup keys for looking up the package root for which there may be a relevant
   * (from the perspective of {@link #getRBuildFiles}) {@link FileValue} node in the graph for
   * {@code originalFileFragment}, which is assumed to be a file path.
   *
   * <p>This is a helper function for {@link #getSkyKeysForFileFragments}.
   */
  private static Iterable<SkyKey> getPkgLookupKeysForFile(PathFragment originalFileFragment,
      PathFragment currentPathFragment) {
    if (originalFileFragment.equals(currentPathFragment)
        && originalFileFragment.equals(Label.EXTERNAL_PACKAGE_FILE_NAME)) {
      Preconditions.checkState(
          Label.EXTERNAL_PACKAGE_FILE_NAME.getParentDirectory().equals(
              PathFragment.EMPTY_FRAGMENT),
          Label.EXTERNAL_PACKAGE_FILE_NAME);
      return ImmutableList.of(
          PackageLookupValue.key(Label.EXTERNAL_PACKAGE_IDENTIFIER),
          PackageLookupValue.key(PackageIdentifier.createInMainRepo(PathFragment.EMPTY_FRAGMENT)));
    }
    PathFragment parentPathFragment = currentPathFragment.getParentDirectory();
    return parentPathFragment == null
        ? ImmutableList.<SkyKey>of()
        : ImmutableList.of(PackageLookupValue.key(
            PackageIdentifier.createInMainRepo(parentPathFragment)));
  }

  /**
   * Returns FileValue keys for which there may be relevant (from the perspective of
   * {@link #getRBuildFiles}) FileValues in the graph corresponding to the given
   * {@code pathFragments}, which are assumed to be file paths.
   *
   * <p>To do this, we emulate the {@link ContainingPackageLookupFunction} logic: for each given
   * file path, we look for the nearest ancestor directory (starting with its parent directory), if
   * any, that has a package. The {@link PackageLookupValue} for this package tells us the package
   * root that we should use for the {@link RootedPath} for the {@link FileValue} key.
   * 
   * Note that there may not be nodes in the graph corresponding to the returned SkyKeys.
   */
  private Collection<SkyKey> getSkyKeysForFileFragments(Iterable<PathFragment> pathFragments) {
    Set<SkyKey> result = new HashSet<>();
    Multimap<PathFragment, PathFragment> currentToOriginal = ArrayListMultimap.create();
    for (PathFragment pathFragment : pathFragments) {
      currentToOriginal.put(pathFragment, pathFragment);
    }
    while (!currentToOriginal.isEmpty()) {
      Multimap<SkyKey, PathFragment> packageLookupKeysToOriginal = ArrayListMultimap.create();
      Multimap<SkyKey, PathFragment> packageLookupKeysToCurrent = ArrayListMultimap.create();
      for (Entry<PathFragment, PathFragment> entry : currentToOriginal.entries()) {
        PathFragment current = entry.getKey();
        PathFragment original = entry.getValue();
        for (SkyKey packageLookupKey : getPkgLookupKeysForFile(original, current)) {
          packageLookupKeysToOriginal.put(packageLookupKey, original);
          packageLookupKeysToCurrent.put(packageLookupKey, current);
        }
      }
      Map<SkyKey, SkyValue> lookupValues =
          graph.getSuccessfulValues(packageLookupKeysToOriginal.keySet());
      for (Map.Entry<SkyKey, SkyValue> entry : lookupValues.entrySet()) {
        SkyKey packageLookupKey = entry.getKey();
        PackageLookupValue packageLookupValue = (PackageLookupValue) entry.getValue();
        if (packageLookupValue.packageExists()) {
          Collection<PathFragment> originalFiles =
              packageLookupKeysToOriginal.get(packageLookupKey);
          Preconditions.checkState(!originalFiles.isEmpty(), entry);
          for (PathFragment fileName : originalFiles) {
            result.add(
                FileValue.key(RootedPath.toRootedPath(packageLookupValue.getRoot(), fileName)));
          }
          for (PathFragment current : packageLookupKeysToCurrent.get(packageLookupKey)) {
            currentToOriginal.removeAll(current);
          }
        }
      }
      Multimap<PathFragment, PathFragment> newCurrentToOriginal = ArrayListMultimap.create();
      for (PathFragment pathFragment : currentToOriginal.keySet()) {
        PathFragment parent = pathFragment.getParentDirectory();
        if (parent != null) {
          newCurrentToOriginal.putAll(parent, currentToOriginal.get(pathFragment));
        }
      }
      currentToOriginal = newCurrentToOriginal;
    }
    return result;
  }

  private static final Function<SkyValue, Package> EXTRACT_PACKAGE =
      new Function<SkyValue, Package>() {
        @Override
        public Package apply(SkyValue skyValue) {
          return ((PackageValue) skyValue).getPackage();
        }
      };

  private static final Predicate<Package> ERROR_FREE_PACKAGE =
      new Predicate<Package>() {
        @Override
        public boolean apply(Package pkg) {
          return !pkg.containsErrors();
        }
      };

  private static final Function<Package, Target> GET_BUILD_FILE =
      new Function<Package, Target>() {
        @Override
        public Target apply(Package pkg) {
          return pkg.getBuildFile();
        }
      };

  private static Iterable<Target> getBuildFilesForPackageValues(Iterable<SkyValue> packageValues) {
    return Iterables.transform(
        Iterables.filter(Iterables.transform(packageValues, EXTRACT_PACKAGE), ERROR_FREE_PACKAGE),
        GET_BUILD_FILE);
  }

  /**
   * Calculates the set of {@link Package} objects, represented as source file targets, that depend
   * on the given list of BUILD files and subincludes (other files are filtered out).
   */
  void getRBuildFiles(Collection<PathFragment> fileIdentifiers, Callback<Target> callback)
      throws QueryException, InterruptedException {
    Collection<SkyKey> files = getSkyKeysForFileFragments(fileIdentifiers);
    Collection<SkyKey> current = graph.getSuccessfulValues(files).keySet();
    Set<SkyKey> resultKeys = CompactHashSet.create();
    while (!current.isEmpty()) {
      Collection<Iterable<SkyKey>> reverseDeps = graph.getReverseDeps(current).values();
      current = new HashSet<>();
      for (SkyKey rdep : Iterables.concat(reverseDeps)) {
        if (rdep.functionName().equals(SkyFunctions.PACKAGE)) {
          resultKeys.add(rdep);
          // Every package has a dep on the external package, so we need to include those edges too.
          if (rdep.equals(PackageValue.key(Label.EXTERNAL_PACKAGE_IDENTIFIER))) {
            current.add(rdep);
          }
        } else if (!rdep.functionName().equals(SkyFunctions.PACKAGE_LOOKUP)) {
          // Packages may depend on the existence of subpackages, but these edges aren't relevant to
          // rbuildfiles.
          current.add(rdep);
        }
      }
      if (resultKeys.size() >= BATCH_CALLBACK_SIZE) {
        for (Iterable<SkyKey> batch : Iterables.partition(resultKeys, BATCH_CALLBACK_SIZE)) {
          callback.process(
              getBuildFilesForPackageValues(graph.getSuccessfulValues(batch).values()));
        }
        resultKeys.clear();
      }
    }
    callback.process(getBuildFilesForPackageValues(graph.getSuccessfulValues(resultKeys).values()));
  }

  @Override
  public Iterable<QueryFunction> getFunctions() {
    return ImmutableList.<QueryFunction>builder()
        .addAll(super.getFunctions())
        .add(new AllRdepsFunction())
        .add(new RBuildFilesFunction())
        .build();
  }

  /**
   * Wraps a {@link Callback} and guarantees that all calls to the original will have at least
   * {@code batchThreshold} {@link Target}s, except for the final such call.
   *
   * <p>Retains fewer than {@code batchThreshold} {@link Target}s at a time.
   *
   * <p>After this object's {@link #process} has been called for the last time, {#link
   * #processLastPending} must be called to "flush" any remaining {@link Target}s through to the
   * original.
   */
  private static class BatchStreamedCallback implements Callback<Target> {

    private final Callback<Target> callback;
    private final Uniquifier<Target> uniquifier;
    private List<Target> pending = new ArrayList<>();
    private int batchThreshold;

    private BatchStreamedCallback(Callback<Target> callback, int batchThreshold,
        Uniquifier<Target> uniquifier) {
      this.callback = callback;
      this.batchThreshold = batchThreshold;
      this.uniquifier = uniquifier;
    }

    @Override
    public void process(Iterable<Target> partialResult)
        throws QueryException, InterruptedException {
      Preconditions.checkNotNull(pending, "Reuse of the callback is not allowed");
      pending.addAll(uniquifier.unique(partialResult));
      if (pending.size() >= batchThreshold) {
        callback.process(pending);
        pending = new ArrayList<>();
      }
    }

    private void processLastPending() throws QueryException, InterruptedException {
      if (!pending.isEmpty()) {
        callback.process(pending);
        pending = null;
      }
    }
  }
}
