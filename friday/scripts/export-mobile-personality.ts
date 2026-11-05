import fs from 'node:fs/promises';
import { MYRAA_PERSONALITY } from '../shared/conversation';
await fs.writeFile('android/app/src/main/assets/personality.txt', MYRAA_PERSONALITY);
