const HTML_TEXT_CHARACTERS = /[&<>"']/g;
const HTML_TEXT_ENTITIES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;'
};

export function escapeHtmlText(value: string) {
  return value.replace(HTML_TEXT_CHARACTERS, character => HTML_TEXT_ENTITIES[character]);
}
