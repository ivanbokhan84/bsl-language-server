/*
 * This file is a part of BSL Language Server.
 *
 * Copyright (c) 2018-2026
 * Alexey Sosnoviy <labotamy@gmail.com>, Nikita Fedkin <nixel2007@gmail.com> and contributors
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * BSL Language Server is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * BSL Language Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with BSL Language Server.
 */
package com.github._1c_syntax.bsl.languageserver.cli;

import com.github._1c_syntax.bsl.languageserver.reporters.DiagnosticReporter;
import com.github._1c_syntax.bsl.languageserver.reporters.ReportersAggregator;
import com.github._1c_syntax.bsl.languageserver.reporters.data.AnalysisInfo;
import com.github._1c_syntax.bsl.languageserver.reporters.data.FileInfo;
import com.github._1c_syntax.bsl.languageserver.util.CleanupContextBeforeClassAndAfterEachTestMethod;
import com.github._1c_syntax.bsl.languageserver.util.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code analyze --target}: отчёт только по целевым файлам, остальные файлы каталога — контекст.
 */
@SpringBootTest
@CleanupContextBeforeClassAndAfterEachTestMethod
class AnalyzeCommandTargetTest {

  private static final Path METADATA = Path.of(TestUtils.PATH_TO_METADATA).toAbsolutePath();
  private static final Path TARGET = METADATA.resolve(Path.of("Catalogs", "Справочник1", "Ext", "ObjectModule.bsl"));
  private static final Path OTHER_TARGET =
    METADATA.resolve(Path.of("Documents", "Документ1", "Forms", "ФормаДокумента", "Ext", "Form", "Module.bsl"));

  @Autowired
  private AnalyzeCommand analyzeCommand;

  @Autowired
  private ReportersAggregator aggregator;

  @TempDir
  Path tempDir;

  private final CapturingReporter reporter = new CapturingReporter();

  private Object originalReporters;

  @BeforeEach
  void prepareAnalysis() {
    originalReporters = ReflectionTestUtils.getField(aggregator, "filteredReporters");
    ReflectionTestUtils.setField(analyzeCommand, "srcDirOption", METADATA.toString());
    ReflectionTestUtils.setField(analyzeCommand, "workspaceDirOption", METADATA.toString());
    ReflectionTestUtils.setField(analyzeCommand, "outputDirOption", tempDir.toString());
    ReflectionTestUtils.setField(analyzeCommand, "configurationOption", "");
    ReflectionTestUtils.setField(analyzeCommand, "silentMode", true);
    ReflectionTestUtils.setField(analyzeCommand, "targetOptions", new String[0]);
    ReflectionTestUtils.setField(aggregator, "filteredReporters", List.of(reporter));
  }

  /** Бины команды и агрегатора общие для тестового контекста: вернуть их состояние другим тестам. */
  @AfterEach
  void restoreBeans() {
    ReflectionTestUtils.setField(analyzeCommand, "targetOptions", new String[0]);
    ReflectionTestUtils.setField(aggregator, "filteredReporters", originalReporters);
  }

  /** Отчёт с целями содержит только цели, и их диагностики совпадают с полным анализом каталога. */
  @Test
  void targetsReportOnlyTargetFilesWithSameDiagnostics() {
    // given: полный анализ каталога
    assertThat(analyzeCommand.call()).isZero();
    var full = List.copyOf(reporter.captured());
    assertThat(full).hasSizeGreaterThan(2);

    // when: повторная цель не дублируется
    ReflectionTestUtils.setField(analyzeCommand, "targetOptions",
      new String[]{TARGET.toString(), OTHER_TARGET.toString(), TARGET.toString()});
    var exitCode = analyzeCommand.call();

    // then
    assertThat(exitCode).isZero();
    var targeted = reporter.captured();
    assertThat(targeted).extracting(FileInfo::getPath)
      .containsExactly(METADATA.relativize(TARGET), METADATA.relativize(OTHER_TARGET));
    for (var fileInfo : targeted) {
      var sameFile = full.stream().filter(info -> info.getPath().equals(fileInfo.getPath())).findFirst().orElseThrow();
      assertThat(fileInfo.getDiagnostics()).containsExactlyInAnyOrderElementsOf(sameFile.getDiagnostics());
      assertThat(fileInfo.getMdoRef()).isEqualTo(sameFile.getMdoRef());
    }
  }

  /** Цель вне анализируемых файлов каталога — код 1, отчёт не строится. */
  @Test
  void targetOutsideSourceFilesReturnsOne() {
    // given
    var outside = tempDir.resolve("Outside.bsl");
    ReflectionTestUtils.setField(analyzeCommand, "targetOptions", new String[]{outside.toString()});

    // when
    var exitCode = analyzeCommand.call();

    // then
    assertThat(exitCode).isOne();
    assertThat(reporter.captured()).isEmpty();
  }

  /** Тестовый репортер: не требует метрик и сохраняет полученные {@link FileInfo}. */
  private static class CapturingReporter implements DiagnosticReporter {

    private final List<FileInfo> captured = new CopyOnWriteArrayList<>();

    @Override
    public String key() {
      return "capturing";
    }

    @Override
    public void report(AnalysisInfo analysisInfo, Path outputDir) {
      captured.clear();
      captured.addAll(analysisInfo.fileinfos());
    }

    List<FileInfo> captured() {
      return captured;
    }
  }
}
