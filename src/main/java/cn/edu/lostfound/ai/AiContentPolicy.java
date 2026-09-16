package cn.edu.lostfound.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded surface editing and extractive guidance, not a semantic safety classifier. */
public final class AiContentPolicy {
  private static final Pattern UNSAFE_FORMAT = Pattern.compile("[<>`]|https?://|javascript:|data:", Pattern.CASE_INSENSITIVE);
  private static final Pattern EDIT_INSTRUCTIONS = Pattern.compile("忽略.{0,12}(前文|规则|指令)|系统提示|扮演|宣称.{0,12}(已|完成)|输出.{0,12}(网页|代码|脚本)|ignore.{0,24}(instructions|rules)|system\\s*prompt", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private final String polishPrompt;
  private final String chatPrompt;
  private final List<String> statements;

  public AiContentPolicy(ObjectMapper mapper) {
    try (var resource = AiContentPolicy.class.getResourceAsStream("/ai-content-policy.json")) {
      if (resource == null) throw new IOException("Missing AI content policy");
      var root = mapper.readTree(resource);
      polishPrompt = root.path("polishPrompt").asText();
      var entries = new ArrayList<String>();
      root.path("guideStatements").forEach(node -> entries.add(node.asText()));
      if (polishPrompt.isBlank() || entries.size() != 8 || entries.stream().anyMatch(String::isBlank))
        throw new IOException("Invalid AI content policy");
      statements = List.copyOf(entries);
      chatPrompt = root.path("chatPrompt").asText() + "\n" + String.join("\n", statements);
    } catch (IOException e) { throw new IllegalStateException("Cannot load AI content policy", e); }
  }

  public String system(boolean polish) { return polish ? polishPrompt : chatPrompt; }

  public boolean accepts(String input, String output, boolean polish) {
    if (input == null || output == null || output.isBlank() || unsafe(output)) return false;
    if (polish) {
      // Do not allow changing decimal points, signs, separators between digits,
      // negations, words, numbers or their order. Even accepted text needs user review.
      return !blockedPolishInput(input) && surface(input).equals(surface(output));
    }
    // Guidance is exactly 1-3 reviewed statements, never free-form role/fact claims.
    var lines = output.strip().split("\\R");
    if (lines.length < 1 || lines.length > 3) return false;
    var used = new HashSet<String>();
    for (String line : lines) {
      String text = line.strip();
      if (!statements.contains(text) || !used.add(text)) return false;
    }
    return true;
  }

  public boolean unsafe(String text) {
    return UNSAFE_FORMAT.matcher(text).find() || text.codePoints().anyMatch(c ->
        Character.getType(c) == Character.FORMAT || (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'));
  }
  public boolean blockedPolishInput(String text) { return unsafe(text) || EDIT_INSTRUCTIONS.matcher(text).find(); }

  private String surface(String text) {
    StringBuilder normalized = new StringBuilder();
    int[] points = text.codePoints().toArray();
    for (int i = 0; i < points.length; i++) {
      int c = points[i];
      int left = i - 1, right = i + 1;
      while (left >= 0 && space(points[left])) left--;
      while (right < points.length && space(points[right])) right++;
      if (space(c)) {
        if (left >= 0 && right < points.length && asciiWord(points[left]) && asciiWord(points[right]) &&
            (normalized.isEmpty() || normalized.charAt(normalized.length() - 1) != ' ')) normalized.append(' ');
        continue;
      }
      boolean betweenDigits = left >= 0 && right < points.length && Character.isDigit(points[left]) && Character.isDigit(points[right]);
      if (!betweenDigits && "，,。；;！!？?、".indexOf(c) >= 0) continue;
      normalized.appendCodePoint(c);
    }
    return normalized.toString();
  }
  private boolean space(int c) { return Character.isWhitespace(c) || Character.isSpaceChar(c); }
  private boolean asciiWord(int c) { return c < 128 && Character.isLetterOrDigit(c); }
}
