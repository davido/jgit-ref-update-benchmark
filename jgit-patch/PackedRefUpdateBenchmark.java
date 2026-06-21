/*
 * Copyright (C) 2026, David Ostrovsky <david@ostrovsky.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.benchmarks;

import static org.eclipse.jgit.transport.ReceiveCommand.Type.UPDATE;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Compare {@link RefUpdate#update()} against {@link BatchRefUpdate#execute}
 * with one {@link ReceiveCommand} (the N=1 case) for the UPDATE-of-existing-ref
 * scenario on packed refs, parametrised by preexisting branch count.
 *
 * <p>
 * Motivation: on RefDirectory, {@code PackedBatchRefUpdate#execute}
 * short-circuits at {@code pending.size() == 1} to
 * {@code super.execute(...)} ("Single-ref updates are always atomic, no need
 * for packed-refs."), so a 1-command BatchRefUpdate bottoms out in the same
 * {@code RefDirectoryUpdate} machinery as a direct {@code RefUpdate}. This
 * benchmark quantifies the constant-factor difference on RefDirectory.
 *
 * <p>
 * Scenario: update an existing branch from the prepopulated set, advancing it
 * from {@code firstCommit} to {@code secondCommit}. On RefDirectory the
 * prepopulated refs land in {@code packed-refs}; the update writes a loose ref
 * shadowing the packed entry.
 *
 * <p>
 * Each invocation consumes a unique prepopulated ref. Reusing a ref would turn
 * the direct {@link RefUpdate} path into a no-op and the
 * {@link BatchRefUpdate} path into an expected-old-id failure, so this
 * benchmark deliberately omits small branch-count parameters that can be
 * exhausted during a trial.
 */
@State(Scope.Thread)
public class PackedRefUpdateBenchmark {

	@State(Scope.Benchmark)
	public static class BenchmarkState {

		@Param({ "1000000", "2000000" })
		int numBranches;

		RefBenchmarkSupport.BenchmarkRepository repo;

		final AtomicLong updateCounter = new AtomicLong();

		@Setup
		public void setupBenchmark() throws IOException, GitAPIException {
			repo = RefBenchmarkSupport.setupBenchmark(numBranches, true, false);
		}

		@TearDown
		public void teardown() throws IOException {
			repo.close();
		}
	}

	@Benchmark
	@BenchmarkMode({ Mode.AverageTime })
	@OutputTimeUnit(TimeUnit.MICROSECONDS)
	@Warmup(iterations = 2, time = 100, timeUnit = TimeUnit.MILLISECONDS)
	@Measurement(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
	@Fork(2)
	public void updatePackedViaRefUpdate(Blackhole blackhole,
			BenchmarkState state) throws IOException {
		String refName = nextRefName(state);
		RefUpdate ru = state.repo.repo.updateRef(refName);
		ru.setNewObjectId(state.repo.secondCommit);
		ru.setExpectedOldObjectId(state.repo.firstCommit);
		ru.setForceUpdate(true);
		RefUpdate.Result result = ru.update();
		if (result != RefUpdate.Result.FORCED
				&& result != RefUpdate.Result.FAST_FORWARD) {
			throw new IllegalStateException(
					"Unexpected RefUpdate result: " + result);
		}
		blackhole.consume(result);
	}

	@Benchmark
	@BenchmarkMode({ Mode.AverageTime })
	@OutputTimeUnit(TimeUnit.MICROSECONDS)
	@Warmup(iterations = 2, time = 100, timeUnit = TimeUnit.MILLISECONDS)
	@Measurement(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
	@Fork(2)
	public void updatePackedViaBatchRefUpdate(Blackhole blackhole,
			BenchmarkState state) throws IOException {
		String refName = nextRefName(state);
		BatchRefUpdate bru = state.repo.repo.getRefDatabase().newBatchUpdate();
		bru.setAllowNonFastForwards(true);
		bru.addCommand(new ReceiveCommand(state.repo.firstCommit,
				state.repo.secondCommit, refName, UPDATE));
		try (RevWalk rw = new RevWalk(state.repo.repo)) {
			bru.execute(rw, NullProgressMonitor.INSTANCE);
		}
		ReceiveCommand.Result result = bru.getCommands().get(0).getResult();
		if (result != ReceiveCommand.Result.OK) {
			throw new IllegalStateException(
					"Unexpected BatchRefUpdate result: " + result);
		}
		blackhole.consume(result);
	}

	private String nextRefName(BenchmarkState state) {
		long next = state.updateCounter.getAndIncrement();
		if (next >= state.numBranches) {
			throw new IllegalStateException("Exhausted " + state.numBranches
					+ " prepopulated refs");
		}
		return Constants.R_HEADS + state.repo.branches.get((int) next);
	}

	public static void main(String[] args) throws RunnerException {
		Options opt = new OptionsBuilder()
				.include(PackedRefUpdateBenchmark.class.getSimpleName())
				// .addProfiler(StackProfiler.class)
				// .addProfiler(GCProfiler.class)
				.forks(1).jvmArgs("-ea").build();
		new Runner(opt).run();
	}
}
