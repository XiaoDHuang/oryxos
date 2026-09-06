package com.oryxos.tool.sandbox;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.regex.Pattern;

/**
 * 只开放显式HTTP目标，其他动作继续沿用核心阶段的默认拒绝. 域名集合启动期载入后支持运行期 增删(并发结构,内存覆盖语义);enforce 的严格规范化与查重规则不变。
 *
 * @author OryxOS Contributors
 */
public final class HttpWhitelistSandbox implements Sandbox {

  private static final int MAX_PORT = 65535;
  private static final int MAX_HOST_LENGTH = 253;
  private static final int IPV4_COMPONENTS = 4;
  private static final int IPV4_COMPONENT_MAX = 255;
  private static final int MIN_BRACKETED_HOST_LENGTH = 2;
  private static final String COLON = ":";
  private static final char COLON_CHARACTER = ':';
  private static final String DOT = ".";
  private static final char DOT_CHARACTER = '.';
  private static final String DOT_PATTERN = "\\.";
  private static final String OPEN_BRACKET = "[";
  private static final String CLOSE_BRACKET = "]";
  private static final char CLOSE_BRACKET_CHARACTER = ']';
  private static final String DENIED = "HTTP目标不在允许列表中";
  private static final Pattern WEB_SCHEME = Pattern.compile("https?", Pattern.CASE_INSENSITIVE);
  private static final Pattern DNS_LABEL =
      Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", Pattern.CASE_INSENSITIVE);
  private final Set<String> allowedHosts;
  private final Map<String, String> displayByKey;

  /** 坏项或规范化后重复会拒绝整份配置，避免最后一项覆盖安全策略. */
  public HttpWhitelistSandbox(Collection<String> allowedDomains) {
    if (allowedDomains == null) {
      throw invalidConfiguration();
    }
    Set<String> normalized = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    Map<String, String> display = new ConcurrentHashMap<>();
    try {
      for (String domain : allowedDomains) {
        String key = normalizeHost(domain);
        if (!normalized.add(key)) {
          throw invalidConfiguration();
        }
        display.put(key, domain);
      }
    } catch (IllegalArgumentException exception) {
      throw invalidConfiguration();
    }
    // 运行期可增删:并发跳表,读(enforce)写(管理端点)互不阻塞;enforce 用规范化键,视图用原始文本.
    // 比较器必须与启动期 TreeSet 同为 CASE_INSENSITIVE_ORDER,否则大小写规范化语义被破坏
    allowedHosts = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);
    allowedHosts.addAll(normalized);
    displayByKey = display;
  }

  /** 返回生效域名视图(管理员可读的原始条目快照,排序稳定). */
  public Set<String> allowedHostView() {
    Set<String> view = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    view.addAll(displayByKey.values());
    return java.util.Collections.unmodifiableSet(view);
  }

  /** 运行期新增域名;规范化与启动期同规则,坏项抛 IllegalArgumentException,已存在返回 false. */
  public boolean allowDomain(String domain) {
    String key;
    try {
      key = normalizeHost(domain);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("HTTP允许域名项无效: " + domain, exception);
    }
    boolean added = allowedHosts.add(key);
    if (added) {
      displayByKey.put(key, domain);
    }
    return added;
  }

  /** 运行期删除域名;入参无法规范化按"不存在"处理,返回 false. */
  public boolean denyDomain(String domain) {
    String key;
    try {
      key = normalizeHost(domain);
    } catch (IllegalArgumentException exception) {
      return false;
    }
    boolean removed = allowedHosts.remove(key);
    if (removed) {
      displayByKey.remove(key);
    }
    return removed;
  }

  @Override
  public void enforce(SandboxAction action) {
    if (action == null || action.type() != ActionType.HTTP_REQUEST) {
      throw new SandboxViolationException(DENIED);
    }
    try {
      URI uri = URI.create(action.target());
      String scheme = uri.getScheme();
      if (scheme == null) {
        throw new IllegalArgumentException();
      }
      if (!WEB_SCHEME.matcher(scheme).matches()) {
        throw new IllegalArgumentException();
      }
      if (uri.getRawAuthority() == null) {
        throw new IllegalArgumentException();
      }
      if (uri.getRawUserInfo() != null) {
        throw new IllegalArgumentException();
      }
      if (uri.getRawFragment() != null) {
        throw new IllegalArgumentException();
      }
      Authority authority = parseAuthority(uri.getRawAuthority());
      if (!allowedHosts.contains(normalizeHost(authority.host()))) {
        throw new IllegalArgumentException();
      }
    } catch (IllegalArgumentException exception) {
      throw new SandboxViolationException(DENIED);
    }
  }

  private static Authority parseAuthority(String value) {
    if (value.isBlank()
        || !value.equals(value.trim())
        || value.contains("@")
        || value.contains("%")) {
      throw new IllegalArgumentException();
    }
    String host;
    String port = null;
    if (value.startsWith(OPEN_BRACKET)) {
      int end = value.indexOf(CLOSE_BRACKET_CHARACTER);
      if (end <= 1) {
        throw new IllegalArgumentException();
      }
      host = value.substring(1, end);
      String suffix = value.substring(end + 1);
      if (!suffix.isEmpty()) {
        if (!suffix.startsWith(COLON) || suffix.length() == 1) {
          throw new IllegalArgumentException();
        }
        port = suffix.substring(1);
      }
    } else {
      int colon = value.lastIndexOf(COLON_CHARACTER);
      if (colon >= 0) {
        if (value.indexOf(COLON_CHARACTER) != colon) {
          throw new IllegalArgumentException();
        }
        host = value.substring(0, colon);
        port = value.substring(colon + 1);
      } else {
        host = value;
      }
    }
    if (port != null) {
      try {
        int number = Integer.parseInt(port);
        if (number < 1 || number > MAX_PORT || !Integer.toString(number).equals(port)) {
          throw new IllegalArgumentException();
        }
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException();
      }
    }
    return new Authority(host);
  }

  @SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification = "IDN先转为ASCII并校验每个DNS标签，比较阶段不处理未规范化Unicode")
  private static String normalizeHost(String value) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.trim())
        || value.length() > MAX_HOST_LENGTH
        || value.contains("/")
        || value.contains("\\")
        || value.contains("@")
        || value.contains("*")
        || value.contains("%")) {
      throw new IllegalArgumentException();
    }
    String host = stripBrackets(value);
    if (host.contains(COLON)) {
      try {
        InetAddress address = InetAddress.getByName(host);
        if (!(address instanceof Inet6Address)) {
          throw new IllegalArgumentException();
        }
        return "ip:" + bytesKey(address.getAddress());
      } catch (UnknownHostException exception) {
        throw new IllegalArgumentException();
      }
    }
    if (host.chars()
        .allMatch(character -> Character.isDigit(character) || character == DOT_CHARACTER)) {
      String[] parts = host.split(DOT_PATTERN, -1);
      if (parts.length != IPV4_COMPONENTS) {
        throw new IllegalArgumentException();
      }
      byte[] bytes = new byte[IPV4_COMPONENTS];
      for (int index = 0; index < parts.length; index++) {
        String part = parts[index];
        if (!part.matches("0|[1-9][0-9]{0,2}")) {
          throw new IllegalArgumentException();
        }
        int number = Integer.parseInt(part);
        if (number > IPV4_COMPONENT_MAX) {
          throw new IllegalArgumentException();
        }
        bytes[index] = (byte) number;
      }
      return "ip:" + bytesKey(bytes);
    }
    String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
    if (ascii.endsWith(DOT)) {
      ascii = ascii.substring(0, ascii.length() - 1);
    }
    if (ascii.isEmpty() || ascii.length() > MAX_HOST_LENGTH || !ascii.equals(ascii.trim())) {
      throw new IllegalArgumentException();
    }
    for (String label : ascii.split(DOT_PATTERN, -1)) {
      if (!DNS_LABEL.matcher(label).matches()) {
        throw new IllegalArgumentException();
      }
    }
    return "dns:" + ascii;
  }

  private static String stripBrackets(String value) {
    if (value.startsWith(OPEN_BRACKET)
        && value.endsWith(CLOSE_BRACKET)
        && value.length() > MIN_BRACKETED_HOST_LENGTH) {
      return value.substring(1, value.length() - 1);
    }
    if (value.contains(OPEN_BRACKET) || value.contains(CLOSE_BRACKET)) {
      throw new IllegalArgumentException();
    }
    return value;
  }

  private static String bytesKey(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
    }
    return result.toString();
  }

  private static IllegalArgumentException invalidConfiguration() {
    return new IllegalArgumentException("HTTP允许域名配置无效");
  }

  private record Authority(String host) {}
}
