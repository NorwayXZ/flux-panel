package com.admin.service;

import java.util.function.Consumer;

/** Emit immediately except for a suffix that could be the start of a split secret. */
final class AiChatTextRedactor {
  private final String secret;
  private final Consumer<String> output;
  private final StringBuilder held = new StringBuilder();

  AiChatTextRedactor(String secret, Consumer<String> output) {
    this.secret = secret;
    this.output = output;
  }

  void push(String text) {
    held.append(text);
    if (secret.isEmpty()) {
      flush();
      return;
    }
    String safe = held.toString().replace(secret, "[REDACTED]");
    held.setLength(0);
    held.append(safe);
    int keep = 0;
    for (int length = Math.min(secret.length() - 1, held.length()); length > 0; length--)
      if (secret.startsWith(held.substring(held.length() - length))) {
        keep = length;
        break;
      }
    int available = held.length() - keep;
    if (available > 0) {
      output.accept(held.substring(0, available));
      held.delete(0, available);
    }
  }

  void flush() {
    if (!held.isEmpty()) {
      output.accept(
          secret.isEmpty() ? held.toString() : held.toString().replace(secret, "[REDACTED]"));
      held.setLength(0);
    }
  }
}
