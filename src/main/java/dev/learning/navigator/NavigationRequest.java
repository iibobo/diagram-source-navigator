package dev.learning.navigator;

import java.util.List;

public record NavigationRequest(String className, String methodName, List<String> parameterTypes, String fileUrl) {
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
  }
}
