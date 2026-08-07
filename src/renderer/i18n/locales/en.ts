// English translations. Keys are the original Chinese UI strings — i18next
// falls back to the key itself (the Chinese text) when a key has no English
// entry, so zh mode needs no dictionary. Keep keys EXACTLY as they appear in
// the UI (they double as the Chinese source).
import { enSettings } from './en-settings'
import { enLibrary } from './en-library'
import { enReader } from './en-reader'
import { enUi } from './en-ui'
import { enChangelog } from './changelog.en'

export const en: Record<string, string> = {
  ...enSettings,
  ...enLibrary,
  ...enReader,
  ...enUi,
  ...enChangelog,
}
