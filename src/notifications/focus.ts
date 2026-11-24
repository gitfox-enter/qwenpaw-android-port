// Tracks which chat (if any) the user is currently viewing, so task
// completion notifications can be suppressed when the user is already
// watching the run finish instead of being notified after stepping away.

let focusedChatId: string | null = null;

export function markChatFocused(chatId: string | null): void {
  focusedChatId = chatId;
}

export function isChatFocused(chatId: string): boolean {
  return focusedChatId === chatId;
}
