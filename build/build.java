import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.readAllLines;
import static java.nio.file.Files.writeString;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toUnmodifiableMap;
import static java.util.stream.Collectors.toUnmodifiableSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@SuppressWarnings("preview")
class build {
  final static class TextBuilder {
    private final StringBuilder builder = new StringBuilder();
    
    void append(String line) {
      builder.append(line).append("\n");
    }
    
    @Override
    public String toString() {
      return builder.toString();
    }
  }
  
  enum LineKind {
    TEXT,
    SECTION,
    BLANK,
    CODE,
    ;
    
    String clean(String line) {
      return switch(this) {
      case BLANK, CODE -> line;
      case TEXT, SECTION -> line.substring(3); 
      };
    }
    
    static LineKind kind(String line) {
      if (line.startsWith("// #")) {
        return SECTION;
      }
      if (line.startsWith("// ")) {
        return TEXT;
      }
      if (line.isBlank()) {
        return BLANK;
      }
      return CODE;
    }
  }
  
  interface EventHandler {
    default void startDocument() { /*empty*/ }
    default void endDocument() { /*empty*/ }
    default void start(LineKind kind) {
      switch(kind) {
      case CODE -> startCode();
      case TEXT -> startText();
      case SECTION -> startSection();
        //$CASES-OMITTED$
      default -> throw new AssertionError();
      }
    }
    default void end(LineKind kind) {
      switch(kind) {
      case CODE -> endCode();
      case TEXT -> endText();
      case SECTION -> endSection();
        //$CASES-OMITTED$
      default -> throw new AssertionError();
      }
    }
    default void startCode() { /*empty*/ }
    default void endCode() { /*empty*/ }
    default void startText() { /*empty*/ }
    default void endText() { /*empty*/ }
    default void startSection() { /*empty*/ }
    default void endSection() { /*empty*/ }
    void line(LineKind kind, String line);
  }
  
  private static void transformTo(List<String> lines, EventHandler handler) {
    handler.startDocument();
    
    var inside = LineKind.BLANK;
    var insideSection = false;
    var skipFirst = true;
    for(var line: lines) {
      var kind = LineKind.kind(line);   
      
      //System.out.println(kind + " " + line);
      
      inside = switch(kind) {
        case BLANK -> {
          if (inside == LineKind.CODE || inside == LineKind.TEXT) {
            handler.end(inside);
          }
          yield kind;
        }
        case SECTION -> {
          if (inside == LineKind.CODE || inside == LineKind.TEXT) {
            handler.end(inside);
          }
          if (insideSection) {
            handler.end(LineKind.SECTION);
          }
          handler.start(LineKind.SECTION);
          insideSection = true;
          handler.start(LineKind.TEXT);
          yield LineKind.TEXT;
        }
        case TEXT -> {
          if (inside == LineKind.CODE) {
            handler.end(LineKind.CODE);
          }
          if (inside != LineKind.TEXT) {
            handler.start(LineKind.TEXT);
          }
          yield kind;
        }
        case CODE -> {
          if (inside == LineKind.TEXT) {
            handler.end(LineKind.TEXT);
          }
          if (inside != LineKind.CODE) {
            handler.start(LineKind.CODE);
          }
          yield kind;
        }
      };
      
      skipFirst = skipFirst && kind == LineKind.TEXT;
      if (!skipFirst) {
        handler.line(kind, kind.clean(line));
      }
    }
    
    if (inside != LineKind.BLANK) {
      handler.end(inside);
    }
    if (insideSection) {
      handler.end(LineKind.SECTION);
    }
    handler.endDocument();
  }
  
  static void writeMarkDown(List<String> lines, Path to) throws IOException {
    var builder = new TextBuilder();
    transformTo(lines, new EventHandler() {
      @Override
      public void startCode() {
        builder.append("```java");
      }
      @Override
      public void endCode() {
        builder.append("```");
      }
      @Override
      public void line(LineKind kind, String line) {
        builder.append(line);
      }
    });
    writeString(to, builder.toString());
  }
  
  private static String escape(String text) {
    StringBuilder builder = null;
    for(var i = 0; i < text.length(); i++) {
      var c = text.charAt(i);
      switch(c) {
        case '"', '\\' -> {
          if (builder == null) {
            builder = new StringBuilder().append(text, 0, i);
          }
          builder.append(switch(c) {
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            default -> throw new AssertionError();
          });
        }
        default -> {
          if (builder != null) {
            builder.append(c);
          }
        }
      }
    }
    return (builder == null)? text: builder.toString();
  }
  
  static void writeJupyter(List<String> lines, Path to) throws IOException {
    var builder = new TextBuilder();
    transformTo(lines, new EventHandler() {
      private final StringJoiner cells = new StringJoiner(",\n", "[", "]");
      private StringJoiner content;
      
      @Override
      public void start(LineKind kind) {
        content = new StringJoiner(", ", "[", "]");
      }
      
      @Override
      public void endCode() {
        cells.add(String.format(
            """
            {
              "cell_type": "code",
              "execution_count": null,
              "metadata": {},
              "outputs": [],
              "source": %s
            }
            """,
            content.toString()));
        content = null;
      }
      
      @Override
      public void endText() {
        cells.add(String.format(
            """
            {
              "cell_type": "markdown",
              "metadata": {},
              "source": %s
            }
            """,
            content.toString()));
        content = null;
      }
      
      @Override
      public void line(LineKind kind, String line) {
        if (content == null) {  // skip empty lines
          return;
        }
        
        content.add(switch(kind) {
          case BLANK -> "\"\\n\"";
          case CODE, TEXT, SECTION -> "\"" + escape(line) + "\\n\"";
        });
      }
      
      @Override
      public void endDocument() {
        builder.append(String.format(
          """
          {
            "cells": %s,
            "metadata": {
              "kernelspec": {
                "display_name": "Java",
                "language": "java",
                "name": "java"
              },
              "language_info": {
                "codemirror_mode": "java",
                "file_extension": ".java",
                "mimetype": "text/x-java-source",
                "name": "Java",
                "pygments_lexer": "java",
                "version": "15"
              }
            },
            "nbformat": 4,
            "nbformat_minor": 2
          }    
          """, cells.toString()));
      }
    });
    writeString(to, builder.toString());
  }
  
  static void writeJupyterSlideshow(List<String> lines, Path to) throws IOException {
    var builder = new TextBuilder();
    transformTo(lines, new EventHandler() {
      private final StringJoiner cells = new StringJoiner(",\n", "[", "]");
      private StringJoiner content;
      private String textMetadata = "{}";
      
      @Override
      public void start(LineKind kind) {
        EventHandler.super.start(kind);
        content = new StringJoiner(", ", "[", "]");
      }
      
      @Override
      public void startSection() {
        textMetadata =
            """
            {
              "slideshow": {
                "slide_type": "slide"
              }
            }
            """;
      }
      
      @Override
      public void endCode() {
        cells.add(String.format(
            """
            {
              "cell_type": "code",
              "execution_count": null,
              "metadata": {},
              "outputs": [],
              "source": %s
            }
            """,
            content.toString()));
        content = null;
      }
      
      @Override
      public void endText() {
        cells.add(String.format(
            """
            {
              "cell_type": "markdown",
              "metadata": %s,
              "source": %s
            }
            """,
            textMetadata,
            content.toString()));
        textMetadata = "{}";
        content = null;
      }
      
      @Override
      public void line(LineKind kind, String line) {
        if (content == null) {  // skip empty lines
          return;
        }
        
        content.add(switch(kind) {
          case BLANK -> "\"\\n\"";
          case CODE, TEXT, SECTION -> "\"" + escape(line) + "\\n\"";
        });
      }
      
      @Override
      public void endDocument() {
        builder.append(String.format(
          """
          {
            "cells": %s,
            "metadata": {
              "celltoolbar": "Slideshow",
              "kernelspec": {
                "display_name": "Java",
                "language": "java",
                "name": "java"
              },
              "language_info": {
                "codemirror_mode": "java",
                "file_extension": ".java",
                "mimetype": "text/x-java-source",
                "name": "Java",
                "pygments_lexer": "java",
                "version": "15"
              }
            },
            "nbformat": 4,
            "nbformat_minor": 2
          }    
          """, cells.toString()));
      }
    });
    writeString(to, builder.toString());
  }
  
  private static String removeExtension(String filename) {
    var index = filename.lastIndexOf('.');
    if (index == -1) {
      return filename;
    }
    return filename.substring(0, index);
  }
  
  private static String shortName(String filename) {
    var index = filename.indexOf('-');
    if (index == -1) {
      return filename;
    }
    return filename.substring(index + 1);
  }
  
  private interface Generator<T> {
    void generate(List<String> lines, T attachment) throws IOException;
    
    default Generator<T> and(Generator<T> generator) {
      return (lines, attachment) -> {
        generate(lines, attachment);
        generator.generate(lines, attachment);
      };
    }
    
    static Generator<String> write(Generator<Path> writeMethod, Path folder, String extension) {
      return (lines, rawFilename) -> writeMethod.generate(lines, folder.resolve(rawFilename + extension));
    }
  }
  
  private static void generate(List<Path> paths, Generator<String> generator) throws IOException {
    for(var path: paths) {
      var rawFilename = removeExtension(path.getFileName().toString());

      var lines = readAllLines(path);
      generator.generate(lines, rawFilename);
    }
  }
  
  private static void generateIndex(List<Path> files, Path destination, String extension) {
    var index = 0;
    for(var file: files) {
      var filename = removeExtension(file.getFileName().toString()) + extension;
      System.out.println(index + ". [" + shortName(filename) + "](" + destination.getFileName() + "/" + filename + ")");
      index++;
    }
  }
  
  private static List<Path> gatherFiles(Path directory, Predicate<String> filter) throws IOException {
    try(var list = Files.list(directory)) {
      return list.filter(path -> filter.test(path.toString())).sorted(Comparator.comparing(Path::toString)).collect(toList());
    }
  }
  
  private static /*record*/ class Config {
    private enum Kind {
      MARKDOWN(".md", build::writeMarkDown),
      NOTEBOOK(".ipynb", build::writeJupyter),
      SLIDESHOW(".ipynb", build::writeJupyterSlideshow),
      ;
      
      private static final Map<String, Kind> NAME_MAP = stream(values()).collect(Collectors.toMap(k -> k.propertyName, k -> k));
      
      static Optional<Kind> of(String name) {
        return Optional.ofNullable(NAME_MAP.get(name));
      }
      
      private final String propertyName;
      private final String extension;
      private final Generator<Path> generator;
      
      private Kind(String extension, Generator<Path> generator) {
        this.propertyName = name().toLowerCase();
        this.extension = extension;
        this.generator = generator;
      }
      
      Path folder(Properties properties) {
        return Path.of(getProperty(properties, propertyName + ".folder").orElse(propertyName));
      }
    }
    
    private static Optional<String> getProperty(Properties properties, String propertyName) {
      return Optional.ofNullable(properties.getProperty(propertyName));
    }
    
    private final Optional<Kind> index;
    private final Set<Kind> kinds; 
    private final Map<Kind, Path> folderMap;
    
    private Config(Optional<Kind> index, Set<Kind> kinds, Map<Kind, Path> folderMap) {
      this.index = index;
      this.kinds = kinds;
      this.folderMap = folderMap;
    }
    
    Path folder(Kind kind) {
      return folderMap.get(kind);
    }
    
    static Config load(Path propertyPath) throws IOException {
      var properties = new Properties();
      try(var reader = Files.newBufferedReader(propertyPath)) {
        properties.load(reader);
      }
      
      var generate = getProperty(properties, "generate").orElseThrow(() -> { throw new IllegalStateException("no property generate found"); }); 
      var kinds = Arrays.stream(generate.split(",")).flatMap(name -> Kind.of(name).stream()).collect(toUnmodifiableSet());
      var index = getProperty(properties, "index").flatMap(Kind::of);
      var folderMap = Kind.NAME_MAP.values().stream().collect(toUnmodifiableMap(k -> k, k -> k.folder(properties)));
      
      return new Config(index, kinds, folderMap);
    }
  }
  
  public static void main(String[] args) throws IOException {
    var config = Config.load(Path.of("build.property"));
    
    // create folders
    for(var kind: config.kinds) {
      createDirectories(config.folder(kind));
    }
    
    // create generator
    var generator = config.kinds.stream().map(k -> Generator.write(k.generator, config.folder(k), k.extension)).reduce(Generator::and).orElseThrow();

    // gather files and generate    
    var files = gatherFiles(Path.of("."), name -> name.endsWith(".jsh"));
    generate(files, generator);
    
    // generate index
    config.index.ifPresent(index -> generateIndex(files, config.folder(index), index.extension));
  }
} 