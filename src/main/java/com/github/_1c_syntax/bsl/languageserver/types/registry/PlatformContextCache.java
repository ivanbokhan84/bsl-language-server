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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import com.github._1c_syntax.bsl.context.PlatformContextGrabber;
import com.github._1c_syntax.bsl.context.PlatformFinder;
import com.github._1c_syntax.bsl.context.api.AccessMode;
import com.github._1c_syntax.bsl.context.api.Availability;
import com.github._1c_syntax.bsl.context.api.Context;
import com.github._1c_syntax.bsl.context.api.ContextConstructor;
import com.github._1c_syntax.bsl.context.api.ContextEvent;
import com.github._1c_syntax.bsl.context.api.ContextFormParameter;
import com.github._1c_syntax.bsl.context.api.ContextKind;
import com.github._1c_syntax.bsl.context.api.ContextMethod;
import com.github._1c_syntax.bsl.context.api.ContextName;
import com.github._1c_syntax.bsl.context.api.ContextProperty;
import com.github._1c_syntax.bsl.context.api.ContextProvider;
import com.github._1c_syntax.bsl.context.api.LanguageKeywordCategory;
import com.github._1c_syntax.bsl.context.api.LanguageKeywordSnippet;
import com.github._1c_syntax.bsl.context.api.Placeholder;
import com.github._1c_syntax.bsl.context.platform.EnAttachments;
import com.github._1c_syntax.bsl.context.platform.PlatformContextCollection;
import com.github._1c_syntax.bsl.context.platform.PlatformContextConstructor;
import com.github._1c_syntax.bsl.context.platform.PlatformContextEnum;
import com.github._1c_syntax.bsl.context.platform.PlatformContextEnumValue;
import com.github._1c_syntax.bsl.context.platform.PlatformContextEvent;
import com.github._1c_syntax.bsl.context.platform.PlatformContextFormParameter;
import com.github._1c_syntax.bsl.context.platform.PlatformContextMethod;
import com.github._1c_syntax.bsl.context.platform.PlatformContextMethodSignature;
import com.github._1c_syntax.bsl.context.platform.PlatformContextProperty;
import com.github._1c_syntax.bsl.context.platform.PlatformContextProvider;
import com.github._1c_syntax.bsl.context.platform.PlatformContextSignatureParameter;
import com.github._1c_syntax.bsl.context.platform.PlatformContextType;
import com.github._1c_syntax.bsl.context.platform.PlatformGlobalContext;
import com.github._1c_syntax.bsl.context.platform.PlatformLanguageKeyword;
import com.github._1c_syntax.bsl.context.platform.internal.PlatformContextStorage;
import com.github._1c_syntax.bsl.context.platform.primitive.ArbitraryType;
import com.github._1c_syntax.bsl.context.platform.primitive.PrimitivePlaceholderType;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.lsp4j.ServerInfo;
import org.jspecify.annotations.Nullable;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Дисковый кэш разобранного синтакс-помощника 1С (VANTEAM).
 * <p>
 * {@link PlatformContextGrabber#parse()} разбирает десятки тысяч HTML-страниц справки
 * ({@code shcntx_ru.hbk} и {@code shcntx_root.hbk}). Кэш хранит уже построенный
 * {@link PlatformContextProvider} целиком (после двуязычного слияния и разрешения типов) и читает
 * его без разбора справки.
 * <p>
 * Ключ — формат файла, версия BSL LS, версии bsl-context и Kryo, структура классов модели
 * bsl-context, а также путь, размер, время изменения и отпечаток начала и конца каждого файла
 * справки. Смена платформы, обновление справки или движка дают новый ключ; старые файлы удаляются
 * при записи, хранятся последние {@value #KEEP_ENTRIES}.
 * <p>
 * Сериализация — Kryo с обязательной регистрацией классов: читаются только классы модели
 * bsl-context и стандартные коллекции. Записи с циклическими ссылками (record {@link PlatformContextType},
 * списки) создаются до чтения вложенных объектов, поэтому обратные ссылки восстанавливаются.
 * <p>
 * Все сообщения кэша — уровня INFO: промах, порча записи или сбой записи не меняют результат,
 * справка разбирается заново.
 */
@Slf4j
@Component
public class PlatformContextCache {

  static final String FORMAT = "vanteam-platform-context-v1";
  static final int KEEP_ENTRIES = 3;
  private static final byte[] MAGIC = "VBSLHBK1".getBytes(StandardCharsets.US_ASCII);
  private static final String FILE_PREFIX = "platform-context-";
  private static final String FILE_SUFFIX = ".bin";
  private static final List<String> SOURCE_FILES =
    List.of("shcntx_ru.hbk", "shcntx_root.hbk", "shlang_ru.hbk", "shlang_root.hbk");
  private static final int SAMPLE_BYTES = 64 * 1024;
  private static final int BUFFER_SIZE = 1 << 16;
  /**
   * Сериализация графа рекурсивна: контекст тянет методы, их типы возврата — другие контексты.
   * Глубина доходит до тысяч уровней, поэтому чтение и запись идут в потоке с большим стеком
   * (резервируется адресное пространство, а не память).
   */
  private static final long STACK_SIZE = 512L * 1024 * 1024;
  private static final Pattern JAR_NAME = Pattern.compile("([^/!\\\\]+\\.jar)");

  /**
   * Классы модели, сериализуемые полями; порядок задаёт идентификаторы регистрации Kryo
   * и отпечаток структуры в ключе.
   */
  private static final List<Class<?>> FIELD_CLASSES = List.of(
    ContextName.class,
    PlatformContextProvider.class,
    PlatformGlobalContext.class,
    PlatformContextCollection.class,
    PlatformContextEnum.class,
    PlatformContextEnumValue.class,
    PlatformContextMethod.class,
    PlatformContextMethodSignature.class,
    PlatformContextSignatureParameter.class,
    PlatformContextProperty.class,
    PlatformContextEvent.class,
    PlatformContextConstructor.class,
    PlatformContextFormParameter.class,
    PlatformLanguageKeyword.class,
    ArbitraryType.class,
    PrimitivePlaceholderType.class
  );
  private static final List<Class<?>> ENUM_CLASSES = List.of(
    AccessMode.class,
    Availability.class,
    ContextKind.class,
    LanguageKeywordCategory.class
  );
  /**
   * Реализации {@link List}, встречающиеся в модели. Все читаются как {@link ArrayList}: список
   * создаётся до чтения элементов, иначе обратная ссылка на объект, который ещё собирается, читается
   * как {@code null}. Потребители модели списки только читают.
   */
  private static final List<Class<?>> LIST_CLASSES = List.of(
    ArrayList.class,
    LinkedList.class,
    List.of().getClass(),
    List.of(1).getClass(),
    List.of(1, 2, 3).getClass(),
    Stream.of(1).toList().getClass(),
    Collections.emptyList().getClass(),
    Collections.unmodifiableList(new ArrayList<>()).getClass(),
    Collections.synchronizedList(new ArrayList<>()).getClass(),
    Arrays.asList(1).getClass()
  );
  private static final String MODEL_FINGERPRINT = modelFingerprint();

  private final boolean enabled;
  private final Path directory;
  private final String engineVersion;

  public PlatformContextCache(
    @Value("${app.platform-context.cache.enabled:true}") boolean enabled,
    @Value("${app.platform-context.cache.path:}") String path,
    @Value("${app.cache.basePath:${user.home}}") String basePath,
    ServerInfo serverInfo
  ) {
    this.enabled = enabled;
    this.directory = path.isBlank()
      ? Path.of(basePath, ".bsl-language-server", "platform-context")
      : Path.of(path);
    this.engineVersion = Optional.ofNullable(serverInfo.getVersion()).orElse("unknown");
  }

  /**
   * Каталог {@code bin} платформы, справку которой разберёт {@link PlatformContextGrabber}:
   * явный {@code binPath} либо самая свежая установка ({@link PlatformFinder#findLatest()}).
   */
  public static Optional<Path> resolveBinDir(@Nullable Path binPath) {
    if (binPath != null) {
      return Optional.of(binPath);
    }
    return PlatformFinder.findLatest().map(PlatformFinder.PlatformInstall::binDir);
  }

  /**
   * Читает провайдер справки каталога {@code binDir}, если в кэше есть запись с тем же ключом.
   *
   * @return провайдер из кэша; пусто при промахе, отключённом кэше или непригодной записи
   */
  public Optional<ContextProvider> load(Path binDir) {
    if (!enabled) {
      return Optional.empty();
    }
    var key = computeKey(binDir);
    if (key.isEmpty()) {
      return Optional.empty();
    }
    var file = directory.resolve(key.get().fileName());
    if (!Files.isRegularFile(file)) {
      LOGGER.info("Platform context cache miss: {}", file);
      return Optional.empty();
    }
    var start = System.nanoTime();
    try {
      var provider = onLargeStack("platform-context-cache-read", () -> read(file, key.get()));
      LOGGER.info("Platform context cache hit: {} ({} ms)", file, elapsedMillis(start));
      return Optional.of(provider);
    } catch (Exception | StackOverflowError e) {
      restoreInterrupt(e);
      LOGGER.info("Platform context cache unreadable: {} ({}); the syntax helper will be parsed", file, e.toString());
      LOGGER.debug("Platform context cache read failure", e);
      deleteQuietly(file);
      return Optional.empty();
    }
  }

  /**
   * Сохраняет провайдер, только что разобранный из справки каталога {@code binDir}.
   * Запись атомарна: временный файл в том же каталоге переименовывается поверх записи.
   */
  public void store(Path binDir, ContextProvider provider) {
    if (!enabled || !(provider instanceof PlatformContextProvider platformProvider)) {
      return;
    }
    var key = computeKey(binDir);
    if (key.isEmpty()) {
      return;
    }
    var file = directory.resolve(key.get().fileName());
    var start = System.nanoTime();
    Path temp = null;
    try {
      Files.createDirectories(directory);
      temp = Files.createTempFile(directory, file.getFileName().toString(), ".tmp");
      var target = temp;
      onLargeStack("platform-context-cache-write", () -> {
        write(target, key.get(), platformProvider);
        return null;
      });
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      LOGGER.info("Platform context cache written: {} ({} bytes, {} ms)", file, Files.size(file),
        elapsedMillis(start));
      prune(file);
    } catch (Exception | StackOverflowError e) {
      restoreInterrupt(e);
      LOGGER.info("Platform context cache write failed: {} ({})", file, e.toString());
      LOGGER.debug("Platform context cache write failure", e);
      if (temp != null) {
        deleteQuietly(temp);
      }
    }
  }

  /**
   * Ключ записи для каталога справки. Пусто, если в каталоге нет {@code shcntx_ru.hbk}: такой каталог
   * не разберёт и {@link PlatformContextGrabber}, сообщение об этом выдаст он сам.
   */
  Optional<Key> computeKey(Path binDir) {
    try {
      var realBinDir = binDir.toRealPath();
      if (!Files.isRegularFile(realBinDir.resolve(SOURCE_FILES.get(0)))) {
        return Optional.empty();
      }
      var lines = new ArrayList<String>();
      lines.add("format=" + FORMAT);
      lines.add("engine=" + engineVersion);
      lines.add("bsl-context=" + libraryVersion(PlatformContextGrabber.class));
      lines.add("kryo=" + libraryVersion(Kryo.class));
      lines.add("java=" + Runtime.version().feature());
      lines.add("model=" + MODEL_FINGERPRINT);
      for (var name : SOURCE_FILES) {
        var source = realBinDir.resolve(name);
        if (!Files.isRegularFile(source)) {
          lines.add("file=" + name + "|absent");
          continue;
        }
        var attributes = Files.readAttributes(source, BasicFileAttributes.class);
        lines.add("file=" + name + "|" + source + "|" + attributes.size() + "|"
          + attributes.lastModifiedTime().toMillis() + "|" + sampleDigest(source, attributes.size()));
      }
      var text = String.join("\n", lines);
      return Optional.of(new Key(text, FILE_PREFIX + sha256(text.getBytes(StandardCharsets.UTF_8)).substring(0, 32)
        + FILE_SUFFIX));
    } catch (IOException | RuntimeException e) {
      LOGGER.info("Platform context cache key failed for {}: {}", binDir, e.toString());
      return Optional.empty();
    }
  }

  private static PlatformContextProvider read(Path file, Key key) throws IOException {
    try (var input = new Input(Files.newInputStream(file), BUFFER_SIZE)) {
      var magic = input.readBytes(MAGIC.length);
      if (!Arrays.equals(magic, MAGIC)) {
        throw new IOException("not a platform context cache file");
      }
      var storedKey = input.readString();
      if (!key.text().equals(storedKey)) {
        throw new IOException("key mismatch");
      }
      var expectedContexts = input.readVarInt(true);
      var provider = newKryo().readObject(input, PlatformContextProvider.class);
      if (provider.getContexts().size() != expectedContexts) {
        throw new IOException("contexts count mismatch: " + provider.getContexts().size() + " != " + expectedContexts);
      }
      return provider;
    }
  }

  private static void write(Path file, Key key, PlatformContextProvider provider) throws IOException {
    try (var output = new Output(Files.newOutputStream(file), BUFFER_SIZE)) {
      output.writeBytes(MAGIC);
      output.writeString(key.text());
      output.writeVarInt(provider.getContexts().size(), true);
      newKryo().writeObject(output, provider);
    }
  }

  static Kryo newKryo() {
    var kryo = new Kryo();
    kryo.setRegistrationRequired(true);
    kryo.setReferences(true);
    kryo.setClassLoader(PlatformContextProvider.class.getClassLoader());
    kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));

    var listSerializer = new ListSerializer();
    LIST_CLASSES.forEach(type -> kryo.register(type, listSerializer));
    kryo.register(HashMap.class);
    kryo.register(LinkedHashMap.class);
    kryo.register(IdentityHashMap.class);
    ENUM_CLASSES.forEach(kryo::register);
    FIELD_CLASSES.forEach(kryo::register);
    kryo.register(PlatformContextStorage.class, new StorageSerializer());
    kryo.register(PlatformContextType.class, new ContextTypeSerializer());
    kryo.register(EnAttachments.class, new EnAttachmentsSerializer());
    kryo.register(LanguageKeywordSnippet.class, new SnippetSerializer());
    kryo.register(Placeholder.class, new PlaceholderSerializer());
    return kryo;
  }

  /**
   * Удаляет записи сверх {@value #KEEP_ENTRIES} самых свежих и забытые временные файлы старше суток.
   */
  private void prune(Path current) {
    try (var entries = Files.list(directory)) {
      var files = entries.toList();
      var dayAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
      files.stream()
        .filter(path -> path.getFileName().toString().endsWith(".tmp"))
        .filter(path -> lastModified(path).toMillis() < dayAgo)
        .forEach(PlatformContextCache::deleteQuietly);
      files.stream()
        .filter(path -> path.getFileName().toString().startsWith(FILE_PREFIX))
        .filter(path -> path.getFileName().toString().endsWith(FILE_SUFFIX))
        .filter(path -> !path.equals(current))
        .sorted(Comparator.comparing(PlatformContextCache::lastModified, Comparator.reverseOrder()))
        .skip(KEEP_ENTRIES - 1L)
        .forEach(PlatformContextCache::deleteQuietly);
    } catch (IOException | RuntimeException e) {
      LOGGER.debug("Platform context cache prune failed", e);
    }
  }

  private static FileTime lastModified(Path path) {
    try {
      return Files.getLastModifiedTime(path);
    } catch (IOException e) {
      return FileTime.fromMillis(0);
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException | RuntimeException e) {
      LOGGER.debug("Can't delete {}", path, e);
    }
  }

  private static void restoreInterrupt(Throwable e) {
    if (e instanceof InterruptedException) {
      Thread.currentThread().interrupt();
    }
  }

  private static long elapsedMillis(long startNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
  }

  private static <T> T onLargeStack(String name, Callable<T> action) throws Exception {
    var result = new AtomicReference<T>();
    var failure = new AtomicReference<Throwable>();
    var thread = new Thread(null, () -> {
      try {
        result.set(action.call());
      } catch (Throwable e) {
        failure.set(e);
      }
    }, name, STACK_SIZE);
    thread.setDaemon(true);
    thread.setContextClassLoader(PlatformContextCache.class.getClassLoader());
    thread.start();
    thread.join();
    var error = failure.get();
    if (error instanceof Exception exception) {
      throw exception;
    }
    if (error instanceof Error fatal) {
      throw fatal;
    }
    return result.get();
  }

  /**
   * Версия библиотеки: Implementation-Version манифеста либо имя jar-файла из CodeSource
   * (одинаково для исполняемого fat-jar и распакованного layout).
   */
  static String libraryVersion(Class<?> type) {
    var typePackage = type.getPackage();
    var version = typePackage == null ? null : typePackage.getImplementationVersion();
    if (version != null) {
      return version;
    }
    var codeSource = type.getProtectionDomain().getCodeSource();
    if (codeSource == null || codeSource.getLocation() == null) {
      return "unknown";
    }
    var location = codeSource.getLocation().toString();
    var matcher = JAR_NAME.matcher(location);
    String jarName = null;
    while (matcher.find()) {
      jarName = matcher.group(1);
    }
    return jarName == null ? location : jarName;
  }

  private static String modelFingerprint() {
    var text = new StringBuilder();
    Stream.of(FIELD_CLASSES, ENUM_CLASSES, List.<Class<?>>of(PlatformContextStorage.class, PlatformContextType.class,
        EnAttachments.class, LanguageKeywordSnippet.class, Placeholder.class))
      .flatMap(List::stream)
      .forEach((Class<?> type) -> {
        text.append(type.getName()).append('{');
        Arrays.stream(type.getDeclaredFields())
          .filter(field -> !Modifier.isStatic(field.getModifiers()))
          .map(field -> field.getName() + ':' + field.getGenericType().getTypeName())
          .sorted()
          .forEach(field -> text.append(field).append(';'));
        text.append('}');
      });
    return sha256(text.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
  }

  private static String sampleDigest(Path file, long size) throws IOException {
    var digest = messageDigest();
    try (var access = new RandomAccessFile(file.toFile(), "r")) {
      var head = new byte[(int) Math.min(SAMPLE_BYTES, size)];
      access.readFully(head);
      digest.update(head);
      if (size > SAMPLE_BYTES) {
        var tailSize = (int) Math.min(SAMPLE_BYTES, size - SAMPLE_BYTES);
        var tail = new byte[tailSize];
        access.seek(size - tailSize);
        access.readFully(tail);
        digest.update(tail);
      }
    }
    return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
  }

  private static String sha256(byte[] data) {
    return HexFormat.of().formatHex(messageDigest().digest(data));
  }

  private static MessageDigest messageDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Ключ записи: полный текст (хранится в файле и сверяется при чтении) и имя файла по его хешу.
   */
  record Key(String text, String fileName) {
  }

  /**
   * Любой {@link List} модели. Список создаётся и регистрируется как ссылка до чтения элементов.
   */
  private static final class ListSerializer extends Serializer<List<Object>> {

    @Override
    public void write(Kryo kryo, Output output, List<Object> list) {
      output.writeVarInt(list.size(), true);
      for (var element : list) {
        kryo.writeClassAndObject(output, element);
      }
    }

    @Override
    public List<Object> read(Kryo kryo, Input input, Class<? extends List<Object>> type) {
      var size = input.readVarInt(true);
      var list = new ArrayList<Object>(size);
      kryo.reference(list);
      for (var i = 0; i < size; i++) {
        list.add(kryo.readClassAndObject(input));
      }
      return list;
    }
  }

  /**
   * Хранилище пересобирается конструктором из списка контекстов: его индексы имён — {@code TreeMap}
   * с компаратором JDK, который Kryo без открытия модулей не восстановит. Порядок контекстов сохраняется,
   * поэтому индексы «первый побеждает» получаются теми же.
   */
  private static final class StorageSerializer extends Serializer<PlatformContextStorage> {

    @Override
    public void write(Kryo kryo, Output output, PlatformContextStorage storage) {
      var contexts = storage.getContexts();
      if (contexts.stream().anyMatch(PlatformGlobalContext.class::isInstance)) {
        // Конструктор хранилища вынимает первый глобальный контекст из списка; второй изменил бы выбор.
        throw new IllegalStateException("more than one global context");
      }
      output.writeVarInt(contexts.size(), true);
      for (var context : contexts) {
        kryo.writeClassAndObject(output, context);
      }
      kryo.writeClassAndObject(output, storage.getGlobalContext());
    }

    @Override
    public PlatformContextStorage read(Kryo kryo, Input input, Class<? extends PlatformContextStorage> type) {
      var size = input.readVarInt(true);
      var contexts = new ArrayList<Context>(size + 1);
      for (var i = 0; i < size; i++) {
        contexts.add((Context) kryo.readClassAndObject(input));
      }
      var globalContext = (PlatformGlobalContext) kryo.readClassAndObject(input);
      if (globalContext != null) {
        contexts.add(globalContext);
      }
      return new PlatformContextStorage(contexts);
    }
  }

  /**
   * Record типа платформы: запись создаётся с пустыми списками и регистрируется как ссылка, затем
   * списки заполняются — методы типа могут возвращать сам тип.
   */
  private static final class ContextTypeSerializer extends Serializer<PlatformContextType> {

    @Override
    public void write(Kryo kryo, Output output, PlatformContextType type) {
      kryo.writeObjectOrNull(output, type.name(), ContextName.class);
      output.writeString(type.description());
      output.writeString(type.notes());
      output.writeString(type.sinceVersion());
      output.writeString(type.deprecatedSinceVersion());
      output.writeString(type.pagePath());
      writeElements(kryo, output, type.methods());
      writeElements(kryo, output, type.constructors());
      writeElements(kryo, output, type.events());
      writeElements(kryo, output, type.properties());
      writeElements(kryo, output, type.formParameters());
      writeElements(kryo, output, type.availabilities());
      writeElements(kryo, output, type.examples());
      writeElements(kryo, output, type.seeAlso());
      writeElements(kryo, output, type.recommendedReplacements());
    }

    @Override
    public PlatformContextType read(Kryo kryo, Input input, Class<? extends PlatformContextType> type) {
      var name = kryo.readObjectOrNull(input, ContextName.class);
      var description = input.readString();
      var notes = input.readString();
      var sinceVersion = input.readString();
      var deprecatedSinceVersion = input.readString();
      var pagePath = input.readString();
      var methods = new ArrayList<ContextMethod>();
      var constructors = new ArrayList<ContextConstructor>();
      var events = new ArrayList<ContextEvent>();
      var properties = new ArrayList<ContextProperty>();
      var formParameters = new ArrayList<ContextFormParameter>();
      var availabilities = new ArrayList<Availability>();
      var examples = new ArrayList<String>();
      var seeAlso = new ArrayList<String>();
      var recommendedReplacements = new ArrayList<String>();
      var contextType = new PlatformContextType(name, methods, constructors, events, properties, formParameters,
        description, notes, availabilities, sinceVersion, deprecatedSinceVersion, examples, seeAlso,
        recommendedReplacements, pagePath);
      kryo.reference(contextType);
      readElements(kryo, input, methods, ContextMethod.class);
      readElements(kryo, input, constructors, ContextConstructor.class);
      readElements(kryo, input, events, ContextEvent.class);
      readElements(kryo, input, properties, ContextProperty.class);
      readElements(kryo, input, formParameters, ContextFormParameter.class);
      readElements(kryo, input, availabilities, Availability.class);
      readElements(kryo, input, examples, String.class);
      readElements(kryo, input, seeAlso, String.class);
      readElements(kryo, input, recommendedReplacements, String.class);
      return contextType;
    }
  }

  private static final class EnAttachmentsSerializer extends Serializer<EnAttachments> {

    @Override
    public void write(Kryo kryo, Output output, EnAttachments attachments) {
      output.writeString(attachments.description());
      output.writeString(attachments.returnValueDescription());
      output.writeString(attachments.notes());
      writeElements(kryo, output, attachments.examples());
      writeElements(kryo, output, attachments.seeAlso());
      output.writeString(attachments.forEachDescription());
      output.writeString(attachments.indexAccessDescription());
    }

    @Override
    public EnAttachments read(Kryo kryo, Input input, Class<? extends EnAttachments> type) {
      var description = input.readString();
      var returnValueDescription = input.readString();
      var notes = input.readString();
      var examples = new ArrayList<String>();
      readElements(kryo, input, examples, String.class);
      var seeAlso = new ArrayList<String>();
      readElements(kryo, input, seeAlso, String.class);
      var forEachDescription = input.readString();
      var indexAccessDescription = input.readString();
      return new EnAttachments(description, returnValueDescription, notes, examples, seeAlso, forEachDescription,
        indexAccessDescription);
    }
  }

  private static final class SnippetSerializer extends Serializer<LanguageKeywordSnippet> {

    @Override
    public void write(Kryo kryo, Output output, LanguageKeywordSnippet snippet) {
      output.writeString(snippet.ru());
      output.writeString(snippet.en());
    }

    @Override
    public LanguageKeywordSnippet read(Kryo kryo, Input input, Class<? extends LanguageKeywordSnippet> type) {
      var ru = input.readString();
      var en = input.readString();
      if (LanguageKeywordSnippet.EMPTY.ru().equals(ru) && LanguageKeywordSnippet.EMPTY.en().equals(en)) {
        return LanguageKeywordSnippet.EMPTY;
      }
      return new LanguageKeywordSnippet(ru, en);
    }
  }

  private static final class PlaceholderSerializer extends Serializer<Placeholder> {

    @Override
    public void write(Kryo kryo, Output output, Placeholder placeholder) {
      output.writeString(placeholder.name());
      output.writeVarInt(placeholder.start(), false);
      output.writeVarInt(placeholder.end(), false);
    }

    @Override
    public Placeholder read(Kryo kryo, Input input, Class<? extends Placeholder> type) {
      return new Placeholder(input.readString(), input.readVarInt(false), input.readVarInt(false));
    }
  }

  private static void writeElements(Kryo kryo, Output output, List<?> elements) {
    output.writeVarInt(elements.size(), true);
    for (var element : elements) {
      kryo.writeClassAndObject(output, element);
    }
  }

  private static <T> void readElements(Kryo kryo, Input input, List<T> target, Class<T> elementType) {
    var size = input.readVarInt(true);
    for (var i = 0; i < size; i++) {
      target.add(elementType.cast(kryo.readClassAndObject(input)));
    }
  }
}
