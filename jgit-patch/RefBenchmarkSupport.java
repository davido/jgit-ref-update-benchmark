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

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FileUtils;

class RefBenchmarkSupport {
	static class BenchmarkRepository {
		Path testDir;

		Repository repo;

		ObjectId firstCommit;

		ObjectId secondCommit;

		List<String> branches;

		void close() throws IOException {
			if (repo != null) {
				repo.close();
			}
			if (testDir != null) {
				FileUtils.delete(testDir.toFile(),
						FileUtils.RECURSIVE | FileUtils.RETRY);
			}
		}
	}

	static BenchmarkRepository setupBenchmark(int numBranches,
			boolean createSecondCommit, boolean looseRefs)
			throws IOException, GitAPIException {
		BenchmarkRepository result = new BenchmarkRepository();
		try {
			result.testDir = Files.createTempDirectory("jgit-ref-bench-");
			Path workDir = result.testDir.resolve("repo");
			Path repoPath = workDir.resolve(".git");

			try (Git git = Git.init().setDirectory(workDir.toFile()).call()) {
				RevCommit first = git.commit().setMessage("First commit").call();
				result.firstCommit = first.toObjectId();
				if (createSecondCommit) {
					RevCommit second = git.commit().setMessage("Second commit")
							.call();
					result.secondCommit = second.toObjectId();
				}
				git.branchCreate().setName("firstbranch").call();

				git.getRepository().getConfig().setInt(
						ConfigConstants.CONFIG_RECEIVE_SECTION, null,
						"maxCommandBytes", Integer.MAX_VALUE);
				git.getRepository().getConfig().save();
			}

			result.branches = IntStream.range(0, numBranches)
					.mapToObj(i -> "branch/" + i % 100 + "/" + i)
					.collect(Collectors.toList());

			System.out.println("Preparing benchmark repository");
			System.out.println("- repository:  " + repoPath);
			System.out.println("- refDatabase: refdir");
			System.out.println("- refStorage:  " + (looseRefs ? "loose"
					: "packed-refs"));
			System.out.println("- branches:    " + numBranches);

			long startNs = System.nanoTime();
			System.out.print("Populating " + numBranches + " branches ... ");
			if (looseRefs) {
				populateLooseRefs(result, repoPath);
			} else {
				populatePackedRefs(result, repoPath);
			}

			validatePopulation(result);
			long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
			System.out.println("DONE in " + elapsedMs + " ms");
			return result;
		} catch (IOException | GitAPIException e) {
			result.close();
			throw e;
		}
	}

	private static void populatePackedRefs(BenchmarkRepository state,
			Path repoPath) throws IOException {
		List<String> sortedBranches = new ArrayList<>(state.branches);
		sortedBranches.sort(null);
		File packedRefs = repoPath.resolve("packed-refs").toFile();
		String baseSha = state.firstCommit.name();
		try (BufferedWriter w = Files.newBufferedWriter(packedRefs.toPath(),
				StandardCharsets.UTF_8)) {
			w.write("# pack-refs with: peeled fully-peeled sorted \n");
			for (String branch : sortedBranches) {
				w.write(baseSha + " " + Constants.R_HEADS + branch + "\n");
			}
		}
		state.repo = RepositoryCache.open(
				RepositoryCache.FileKey.lenient(repoPath.toFile(), FS.DETECTED));
	}

	private static void populateLooseRefs(BenchmarkRepository state,
			Path repoPath) throws IOException {
		String baseSha = state.firstCommit.name();
		for (String branch : state.branches) {
			Path refFile = repoPath.resolve(Constants.R_HEADS + branch);
			Files.createDirectories(refFile.getParent());
			try (BufferedWriter w = Files.newBufferedWriter(refFile,
					StandardCharsets.UTF_8)) {
				w.write(baseSha);
				w.write('\n');
			}
		}
		state.repo = RepositoryCache.open(
				RepositoryCache.FileKey.lenient(repoPath.toFile(), FS.DETECTED));
	}

	private static void validatePopulation(BenchmarkRepository state)
			throws IOException {
		List<String> samples = List.of(state.branches.get(0),
				state.branches.get(state.branches.size() / 2),
				state.branches.get(state.branches.size() - 1));
		for (String branch : samples) {
			String refName = Constants.R_HEADS + branch;
			Ref ref = state.repo.exactRef(refName);
			if (ref == null || !state.firstCommit.equals(ref.getObjectId())) {
				throw new IllegalStateException(
						"Invalid benchmark setup for " + refName);
			}
		}
	}
}
