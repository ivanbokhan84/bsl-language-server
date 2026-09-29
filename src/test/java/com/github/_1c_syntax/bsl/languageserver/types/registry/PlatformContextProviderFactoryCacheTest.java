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

import com.github._1c_syntax.bsl.context.PlatformContextGrabber;
import com.github._1c_syntax.bsl.context.api.Context;
import com.github._1c_syntax.bsl.context.api.ContextProvider;
import com.github._1c_syntax.bsl.languageserver.configuration.LanguageServerConfiguration;
import com.github._1c_syntax.bsl.languageserver.configuration.platform.V8PlatformOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PlatformContextProviderFactory} с {@link PlatformContextCache}: попадание не разбирает
 * справку, промах разбирает и сохраняет результат.
 */
class PlatformContextProviderFactoryCacheTest {

  private final Path binPath = Path.of("/opt/1cv8/bin");
  private LanguageServerConfiguration configuration;
  private PlatformContextCache cache;

  @BeforeEach
  void setUp() {
    configuration = mock(LanguageServerConfiguration.class);
    var options = mock(V8PlatformOptions.class);
    when(configuration.getV8PlatformOptions()).thenReturn(options);
    when(options.isEnabled()).thenReturn(true);
    when(options.getBinPath()).thenReturn(binPath);
    cache = mock(PlatformContextCache.class);
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void cacheHitSkipsSyntaxHelperParsing() throws IOException {
    // given
    var cached = mock(ContextProvider.class);
    when(cached.getContexts()).thenReturn((List) Collections.<Context>emptyList());
    when(cache.load(binPath)).thenReturn(Optional.of(cached));

    try (MockedStatic<PlatformContextGrabber> grabbers = mockStatic(PlatformContextGrabber.class)) {
      var factory = factory();

      // when
      var result = factory.create();

      // then
      assertThat(result).contains(cached);
      grabbers.verifyNoInteractions();
      verify(cache, never()).store(any(), any());
    }
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void cacheMissParsesSyntaxHelperAndStoresProvider() throws IOException {
    // given
    when(cache.load(binPath)).thenReturn(Optional.empty());
    var grabber = mock(PlatformContextGrabber.class);
    var provider = mock(ContextProvider.class);
    when(grabber.getProvider()).thenReturn(provider);
    when(provider.getContexts()).thenReturn((List) Collections.<Context>emptyList());

    try (MockedStatic<PlatformContextGrabber> grabbers = mockStatic(PlatformContextGrabber.class)) {
      grabbers.when(() -> PlatformContextGrabber.fromPlatformBin(eq(binPath))).thenReturn(grabber);
      var factory = factory();

      // when
      var result = factory.create();

      // then
      assertThat(result).contains(provider);
      verify(grabber).parse();
      verify(cache).store(binPath, provider);
    }
  }

  @Test
  void failedParsingStoresNothing() throws IOException {
    // given
    when(cache.load(binPath)).thenReturn(Optional.empty());
    var grabber = mock(PlatformContextGrabber.class);
    when(grabber.getProvider()).thenReturn(null);

    try (MockedStatic<PlatformContextGrabber> grabbers = mockStatic(PlatformContextGrabber.class)) {
      grabbers.when(() -> PlatformContextGrabber.fromPlatformBin(eq(binPath))).thenReturn(grabber);
      var factory = factory();

      // when
      var result = factory.create();

      // then
      assertThat(result).isEmpty();
      verify(cache, never()).store(any(), any());
    }
  }

  private PlatformContextProviderFactory factory() {
    var factory = new PlatformContextProviderFactory(configuration, Optional.of(cache));
    ReflectionTestUtils.setField(factory, "platformContextEnabled", true);
    return factory;
  }
}
