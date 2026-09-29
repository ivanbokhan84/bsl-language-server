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
package com.github._1c_syntax.bsl.languageserver.types.registry;

import com.github._1c_syntax.bsl.context.api.Context;
import com.github._1c_syntax.bsl.context.api.ContextEnum;
import com.github._1c_syntax.bsl.context.api.ContextEnumValue;
import com.github._1c_syntax.bsl.context.api.ContextName;
import com.github._1c_syntax.bsl.context.api.ContextType;
import com.github._1c_syntax.bsl.context.api.LanguageKeywordCategory;
import com.github._1c_syntax.bsl.context.api.LanguageKeywordSnippet;
import com.github._1c_syntax.bsl.context.platform.EnAttachments;
import com.github._1c_syntax.bsl.context.platform.PlatformContextEnum;
import com.github._1c_syntax.bsl.context.platform.PlatformContextEnumValue;
import com.github._1c_syntax.bsl.context.platform.PlatformContextMethod;
import com.github._1c_syntax.bsl.context.platform.PlatformContextMethodSignature;
import com.github._1c_syntax.bsl.context.platform.PlatformContextProvider;
import com.github._1c_syntax.bsl.context.platform.PlatformContextSignatureParameter;
import com.github._1c_syntax.bsl.context.platform.PlatformContextType;
import com.github._1c_syntax.bsl.context.platform.PlatformGlobalContext;
import com.github._1c_syntax.bsl.context.platform.PlatformLanguageKeyword;
import com.github._1c_syntax.bsl.context.platform.internal.PlatformContextStorage;
import com.github._1c_syntax.bsl.context.platform.primitive.ArbitraryType;
import com.github._1c_syntax.bsl.context.platform.primitive.PrimitivePlaceholderType;
import org.eclipse.lsp4j.ServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Дисковый кэш разобранного синтакс-помощника: восстановление графа контекстов (включая циклы
 * «тип — метод — тип возврата — тот же тип» и en-вложения по идентичности), ключ и инвалидация.
 */
class PlatformContextCacheTest {

  @TempDir
  Path cacheDir;

  @TempDir
  Path binDir;

  @BeforeEach
  void createSyntaxHelperFiles() throws IOException {
    Files.write(binDir.resolve("shcntx_ru.hbk"), bytes(200_000, 1));
    Files.write(binDir.resolve("shcntx_root.hbk"), bytes(150_000, 2));
    Files.write(binDir.resolve("shlang_ru.hbk"), bytes(1_000, 3));
  }

  @Test
  void storedProviderIsReadBackWithSameGraph() {
    // given
    var cache = cache(true);
    var provider = sampleProvider();

    // when
    cache.store(binDir, provider);
    var loaded = cache.load(binDir);

    // then
    assertThat(loaded).containsInstanceOf(PlatformContextProvider.class);
    var restored = (PlatformContextProvider) loaded.orElseThrow();
    assertThat(restored).isNotSameAs(provider);
    assertThat(restored.getContexts()).extracting(context -> context.name().getName())
      .containsExactlyElementsOf(provider.getContexts().stream().map(context -> context.name().getName()).toList());

    var array = (ContextType) restored.getContextByName("массив").orElseThrow();
    var copy = (PlatformContextMethod) array.methods().get(0);
    assertThat(copy.name().getAlias()).isEqualTo("Copy");
    // Цикл через record: тип возврата метода — тот же экземпляр типа.
    assertThat(copy.returnValues()).singleElement().isSameAs(array);
    var parameterTypes = copy.signatures().get(0).parameters().get(0).types();
    assertThat(parameterTypes).singleElement().isSameAs(restored.getContextByName("String").orElseThrow());

    assertThat(restored.getEnAttachments(copy).description()).isEqualTo("Copies the array");
    assertThat(restored.getGlobalContext().methods()).extracting(method -> method.name().getName())
      .containsExactly("Сообщить");
    var questionModes = restored.getContextsByName("QuestionDialogMode");
    assertThat(questionModes).singleElement().isInstanceOf(ContextEnum.class);
    assertThat(((ContextEnum) questionModes.get(0)).values()).extracting(value -> value.name().getName())
      .containsExactly("Да", "Нет");
    var keyword = (PlatformLanguageKeyword) restored.getContextByName("Тогда").orElseThrow();
    assertThat(keyword.snippet()).isSameAs(LanguageKeywordSnippet.EMPTY);
  }

  @Test
  void loadIsMissWhenNothingStored() {
    assertThat(cache(true).load(binDir)).isEmpty();
  }

  @Test
  void changedSyntaxHelperInvalidatesEntry() throws IOException {
    // given
    var cache = cache(true);
    cache.store(binDir, sampleProvider());
    var keyBefore = cache.computeKey(binDir).orElseThrow();

    // when
    var hbk = binDir.resolve("shcntx_ru.hbk");
    Files.setLastModifiedTime(hbk, FileTime.fromMillis(Files.getLastModifiedTime(hbk).toMillis() + 60_000));

    // then
    assertThat(cache.computeKey(binDir).orElseThrow().fileName()).isNotEqualTo(keyBefore.fileName());
    assertThat(cache.load(binDir)).isEmpty();
  }

  @Test
  void otherPlatformDirectoryHasOtherKey(@TempDir Path otherBinDir) throws IOException {
    // given
    Files.write(otherBinDir.resolve("shcntx_ru.hbk"), bytes(200_000, 1));
    var cache = cache(true);

    // when
    cache.store(binDir, sampleProvider());

    // then
    assertThat(cache.computeKey(otherBinDir).orElseThrow().fileName())
      .isNotEqualTo(cache.computeKey(binDir).orElseThrow().fileName());
    assertThat(cache.load(otherBinDir)).isEmpty();
  }

  @Test
  void corruptedEntryIsDeletedAndIgnored() throws IOException {
    // given
    var cache = cache(true);
    cache.store(binDir, sampleProvider());
    var entry = cacheDir.resolve(cache.computeKey(binDir).orElseThrow().fileName());
    var content = Files.readAllBytes(entry);
    Files.write(entry, Arrays.copyOf(content, content.length / 2));

    // when
    var loaded = cache.load(binDir);

    // then
    assertThat(loaded).isEmpty();
    assertThat(entry).doesNotExist();
  }

  @Test
  void disabledCacheNeitherStoresNorLoads() throws IOException {
    // given
    var cache = cache(false);

    // when
    cache.store(binDir, sampleProvider());

    // then
    assertThat(cache.load(binDir)).isEmpty();
    try (var files = Files.list(cacheDir)) {
      assertThat(files).isEmpty();
    }
  }

  @Test
  void directoryWithoutRussianSyntaxHelperHasNoKey(@TempDir Path emptyBinDir) {
    assertThat(cache(true).computeKey(emptyBinDir)).isEmpty();
  }

  @Test
  void onlyLatestEntriesAreKept() throws IOException {
    // given
    var cache = cache(true);
    var provider = sampleProvider();

    // when: каждая запись — новая версия справки
    for (var i = 0; i < PlatformContextCache.KEEP_ENTRIES + 2; i++) {
      var hbk = binDir.resolve("shcntx_ru.hbk");
      Files.setLastModifiedTime(hbk, FileTime.fromMillis(1_700_000_000_000L + i * 60_000L));
      cache.store(binDir, provider);
    }

    // then
    try (var files = Files.list(cacheDir)) {
      assertThat(files.filter(path -> path.getFileName().toString().endsWith(".bin")))
        .hasSize(PlatformContextCache.KEEP_ENTRIES);
    }
    assertThat(cache.load(binDir)).isPresent();
  }

  private PlatformContextCache cache(boolean enabled) {
    return new PlatformContextCache(enabled, cacheDir.toString(), cacheDir.toString(),
      new ServerInfo("BSL Language Server", "test"));
  }

  private static byte[] bytes(int size, int seed) {
    var data = new byte[size];
    for (var i = 0; i < size; i++) {
      data[i] = (byte) (i * 31 + seed);
    }
    return data;
  }

  /**
   * Маленький граф той же формы, что даёт справка: тип-record с методом, возвращающим сам тип,
   * параметр с типом-примитивом, глобальный контекст, перечисление, ключевое слово и en-вложение.
   */
  private static PlatformContextProvider sampleProvider() {
    var string = new PrimitivePlaceholderType(new ContextName("Строка", "String"), "Строка символов");
    var parameter = PlatformContextSignatureParameter.builder()
      .name(new ContextName("Значение", ""))
      .isRequired(true)
      .description("Добавляемое значение")
      .rawTypes(List.of("Строка"))
      .build();
    var signature = PlatformContextMethodSignature.builder()
      .name(new ContextName("Основная", ""))
      .description("")
      .parameters(List.of(parameter))
      .build();
    var copy = PlatformContextMethod.builder()
      .name(new ContextName("Скопировать", "Copy"))
      .description("Копирует массив")
      .availabilities(List.of())
      .signatures(List.of(signature))
      .rawReturnValues(List.of("Массив"))
      .build();
    var array = PlatformContextType.builder()
      .name(new ContextName("Массив", "Array"))
      .methods(new ArrayList<>(List.of(copy)))
      .constructors(List.of())
      .events(List.of())
      .properties(List.of())
      .description("Коллекция значений")
      .build();
    var message = PlatformContextMethod.builder()
      .name(new ContextName("Сообщить", "Message"))
      .description("Выводит сообщение")
      .availabilities(List.of())
      .signatures(List.of())
      .rawReturnValues(List.of())
      .build();
    var global = PlatformGlobalContext.builder()
      .methods(List.of(message))
      .properties(List.of())
      .applicationEvents(List.of())
      .ordinaryApplicationEvents(List.of())
      .sessionModuleEvents(List.of())
      .externalConnectionModuleEvents(List.of())
      .build();
    var questionMode = new PlatformContextEnum(new ContextName("РежимДиалогаВопрос", "QuestionDialogMode"),
      List.<ContextEnumValue>of(new PlatformContextEnumValue(new ContextName("Да", "Yes")),
        new PlatformContextEnumValue(new ContextName("Нет", "No"))));
    var then = new PlatformLanguageKeyword(new ContextName("Тогда", "Then"), LanguageKeywordCategory.values()[0],
      "Начало ветви", LanguageKeywordSnippet.EMPTY);
    var contexts = new ArrayList<Context>(List.of(new ArbitraryType(), string, array, global, questionMode, then));
    var provider = new PlatformContextProvider(new PlatformContextStorage(contexts));
    provider.putEnAttachments(copy, EnAttachments.ofDescription("Copies the array"));
    return provider;
  }
}
