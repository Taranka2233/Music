#!/usr/bin/env node
/**
 * Накатывает натив поверх свежесгенерированной папки android/.
 * Папка android/ в git не хранится — её создаёт `cap add android`,
 * поэтому патч должен быть идемпотентным и переживать пересоздание.
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const cfg = JSON.parse(readFileSync('capacitor.config.json', 'utf8'));
const PKG = cfg.appId;
const JAVA = join('android/app/src/main/java', ...PKG.split('.'));
const MANIFEST = 'android/app/src/main/AndroidManifest.xml';

const ok = m => console.log('  ✓ ' + m);
const skip = m => console.log('  – ' + m);

if (!existsSync('android')) {
  console.error('Нет папки android/. Сначала: npx cap add android');
  process.exit(1);
}

// ── 1. Плагин ────────────────────────────────────────────
mkdirSync(JAVA, { recursive: true });
const plugin = readFileSync('native/MusicScannerPlugin.java', 'utf8').replace('__PKG__', PKG);
writeFileSync(join(JAVA, 'MusicScannerPlugin.java'), plugin);
ok(`MusicScannerPlugin.java → ${JAVA}`);

// ── 2. Регистрация в MainActivity ────────────────────────
const MAIN = join(JAVA, 'MainActivity.java');
let main = readFileSync(MAIN, 'utf8');
if (main.includes('MusicScannerPlugin.class')) {
  skip('MainActivity уже знает про плагин');
} else {
  main = `package ${PKG};

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MusicScannerPlugin.class);   // до super — иначе мост не подхватит
        super.onCreate(savedInstanceState);
    }
}
`;
  writeFileSync(MAIN, main);
  ok('MainActivity регистрирует MusicScanner');
}

// ── 3. Разрешения ────────────────────────────────────────
let man = readFileSync(MANIFEST, 'utf8');
const PERMS = `
    <!-- Android 13+ (API 33): гранулярный доступ к аудио -->
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
    <!-- Android 12 и ниже -->
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />
    <!-- Воспроизведение в фоне -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
`;
if (man.includes('READ_MEDIA_AUDIO')) {
  skip('разрешения уже на месте');
} else {
  man = man.replace('</manifest>', PERMS + '</manifest>');
  writeFileSync(MANIFEST, man);
  ok('READ_MEDIA_AUDIO + фоновое воспроизведение');
}

// ── 4. Проверка ──────────────────────────────────────────
const need = ['READ_MEDIA_AUDIO', 'READ_EXTERNAL_STORAGE'];
const miss = need.filter(p => !readFileSync(MANIFEST, 'utf8').includes(p));
if (miss.length) { console.error('  ✗ не хватает: ' + miss.join(', ')); process.exit(1); }
if (!readFileSync(MAIN, 'utf8').includes('registerPlugin')) { console.error('  ✗ плагин не зарегистрирован'); process.exit(1); }
console.log('\nНатив на месте.');
