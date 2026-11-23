package dev.learning.navigator;

import java.util.List;

public record NavigationRequest(String className, String methodName, List<String> parameterTypes, String fileUrl, Integer line) {
  public NavigationRequest(String className, String methodName, List<String> parameterTypes, String fileUrl) {
    this(className, methodName, parameterTypes, fileUrl, null);
  }

  public NavigationRequest {
    if (className == null || !className.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.$]*")
        || className.length() > 500) throw new IllegalArgumentException("无效类名");
    if (methodName != null && (!methodName.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*")
        || methodName.length() > 200)) throw new IllegalArgumentException("无效方法名");
    if (parameterTypes != null) {
      if (parameterTypes.size() > 64 || parameterTypes.stream().anyMatch(p -> p == null || p.length() > 500))
        throw new IllegalArgumentException("无效参数类型");
      parameterTypes = List.copyOf(parameterTypes);
    }
    if (fileUrl != null && fileUrl.length() > 4096) throw new IllegalArgumentException("无效候选位置");
    if (line != null && (line < 1 || methodName != null || parameterTypes != null))
      throw new IllegalArgumentException("行号必须是正整数，且与类名一起使用");
  }
}
