# GK2A Indonesia: True Color + Animasi (prototipe GitHub Actions)

Proyek ini mengambil citra satelit **GEO-KOMPSAT-2A (GK2A)** lewat internet, tanpa antena dan dekoder HRIT/LRIT.
Datanya dari arsip publik NOAA di AWS (`noaa-gk2a-pds`), yang gratis dan tidak perlu API key.
Setiap 10 menit GitHub Actions membuat citra **true color wilayah Indonesia** beserta **animasinya**,
lalu menerbitkannya di GitHub Pages.

```
AWS S3 (NOAA, data GK2A L1B) ──HTTP range──▶ GitHub Actions (Python + Satpy)
                                                  │  potong wilayah Indonesia, true color,
                                                  │  garis pantai, label waktu, animasi
                                                  ▼
                                   branch gh-pages ──▶ GitHub Pages
                                   (latest.jpg, animation.mp4/gif, frames.json, index.html)
                                                  ▼
                                   Browser / aplikasi Android (nanti)
```

## Hasil

| File | Isi |
|---|---|
| `index.html` | Viewer animasi: play/pause, slider frame, pilihan kecepatan |
| `latest.jpg` | Frame terbaru (lebar 2400 px, kira-kira 2 km/piksel) |
| `animation.mp4`, `animation.gif` | Animasi 3 jam terakhir (18 frame) |
| `frames/gk2a_YYYYMMDDHHMM.jpg` | Frame per 10 menit |
| `frames.json` | Daftar frame dan waktunya, untuk dipakai aplikasi Android nanti |

Produk default adalah `true_color_night_clouds`, komposit buatan sendiri di `satpy_config/`.
Siang hari tampil **true color** (dengan koreksi Rayleigh). Malam hari tampil awan inframerah IR10.5:
awan dingin berwarna putih, permukaan hangat biru gelap. Transisinya mulus di garis terminator,
jadi animasinya tetap berguna 24 jam. Komposit ini tidak butuh data tambahan dari internet.
Kalau produk default gagal dibuat, skrip otomatis mencoba `true_color_raw_night_clouds`, lalu `true_color`,
lalu `true_color_raw`.

## Cara memasang

1. Buat repository **public** baru di GitHub, misalnya `gk2a-indonesia`.
   Repo public mendapat menit GitHub Actions gratis tanpa batas. Repo private hanya punya 2.000 menit per bulan,
   tidak cukup untuk jadwal tiap 10 menit.
2. Upload isi folder ini ke repo tersebut:
   ```bash
   cd gk2a-indonesia
   git init -b main
   git add .
   git commit -m "Prototipe GK2A Indonesia"
   git remote add origin https://github.com/USERNAME/gk2a-indonesia.git
   git push -u origin main
   ```
3. Buka tab **Actions**, pilih workflow **GK2A Indonesia**, lalu klik **Run workflow**.
   Untuk mengisi animasi lebih cepat di awal, isi `max_new` = `6`.
4. Setelah run pertama selesai, branch `gh-pages` akan muncul. Buka **Settings → Pages**, lalu atur:
   *Source: Deploy from a branch*, *Branch: `gh-pages` / `(root)`*.
5. Citra bisa dilihat di `https://USERNAME.github.io/gk2a-indonesia/`.
   Animasi penuh 3 jam akan terisi dalam sekitar 1 jam pertama.

## Pemicu tepat waktu dengan cron-job.org

Jadwal bawaan GitHub Actions sering molor atau terlewat. Supaya citra diperbarui tepat tiap 10 menit,
workflow dipicu dari luar oleh [cron-job.org](https://cron-job.org) (gratis) lewat API GitHub.

1. **Buat token GitHub**: https://github.com/settings/personal-access-tokens/new
   - Repository access: *Only select repositories* → `gk2a-indonesia`
   - Permissions → Repository permissions → **Actions: Read and write** (yang lain biarkan)
2. **Buat cron job** di cron-job.org (*Create cronjob*):
   - URL: `https://api.github.com/repos/8ux/gk2a-indonesia/actions/workflows/gk2a.yml/dispatches`
   - Schedule: *Custom*, menit `4,14,24,34,44,54`, setiap jam, setiap hari
   - Tab *Advanced*: Request method **POST**, request body `{"ref":"main"}`, dan header:
     - `Authorization: Bearer <token>`
     - `Accept: application/vnd.github+json`
     - `Content-Type: application/json`
     - `X-GitHub-Api-Version: 2022-11-28`
3. Jawaban sukses dari GitHub adalah **HTTP 204** (tanpa isi). Jadwal bawaan GitHub tetap ada sebagai cadangan;
   run ganda tidak masalah karena run berikutnya menunggu dan hanya memproses frame yang belum ada.

## Arsip citra

Setiap frame juga disimpan ke **GitHub Releases**, satu release per hari (`archive-YYYYMMDD`, waktu UTC),
selama 30 hari. GitHub Pages dibatasi 1 GB, jadi arsip tidak disimpan di sana. Daftar frame yang tersedia ada di
`archive.json` di situs, yang dipakai aplikasi Android untuk filter tanggal dan jam.

Untuk mengisi arsip tanggal yang sudah lewat (maksimal 30 hari ke belakang, sesuai masa simpan), jalankan workflow
**GK2A Backfill Arsip** dari tab Actions. Isi `end` dengan waktu akhir UTC (`YYYYmmddHHMM`) dan `hours` dengan
jumlah jam ke belakang (maks 24). Prosesnya sekitar 1 menit per frame, atau 6 frame per jam data.
Hasilnya muncul di aplikasi setelah run GK2A Indonesia berikutnya.

## Aplikasi Android: YD1BUX-gk2a

Kode aplikasi ada di folder `android/` (Kotlin, tanpa library tambahan). Setiap ada perubahan di folder itu,
workflow **Android APK** membuat APK dan menerbitkannya di GitHub Releases.

- Unduh APK terbaru: https://github.com/8ux/gk2a-indonesia/releases/latest/download/YD1BUX-gk2a.apk
- Fitur:
  - Animasi frame 3 jam terakhir, cek citra baru otomatis tiap 5 menit.
  - **Arsip**: pilih rentang tanggal & jam (WIB) dengan interval 10 menit, 30 menit, atau 1 jam (maks 144 frame).
  - **Buat GIF** dari rentang tanggal & jam mana pun (arsip + frame live), dengan pilihan kecepatan
    (lambat sampai sangat cepat), lebar 640/800/1080 px, transisi halus (crossfade), putar maju-mundur,
    tahan di frame terbaru, dan potong ke area yang sedang di-zoom. Dialog menampilkan jumlah frame,
    durasi, dan perkiraan ukuran file. GIF disimpan ke galeri lalu bisa langsung dibagikan.
  - Pinch-zoom (otomatis memuat resolusi penuh saat dijeda), pilihan kecepatan, simpan gambar ke galeri.
  - Simpan gambar dan GIF butuh Android 10+.
- Bahasa: Inggris, atau Indonesia bila bahasa HP diatur ke Bahasa Indonesia.
- Minimal Android 8.0.

**Tanda tangan APK.** Supaya versi baru bisa dipasang menimpa versi lama, setiap build harus ditandatangani dengan
keystore yang sama. Keystore itu disimpan sebagai GitHub Secret, bukan di repo. Buat sekali di komputer Anda:

```bash
keytool -genkeypair -keystore yd1bux-gk2a.jks -storetype PKCS12 -alias yd1bux -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=YD1BUX-gk2a"
```

```bash
base64 -w0 yd1bux-gk2a.jks | gh secret set SIGNING_KEYSTORE_B64 --repo 8ux/gk2a-indonesia
```

```bash
gh secret set SIGNING_STORE_PASSWORD --repo 8ux/gk2a-indonesia
```

```bash
gh secret set SIGNING_KEY_PASSWORD --repo 8ux/gk2a-indonesia
```

Isi kedua password dengan password yang Anda pakai saat `keytool`. Simpan file `yd1bux-gk2a.jks` di tempat aman
dan jangan di-commit. Tanpa secret ini, APK ditandatangani dengan debug keystore yang berbeda tiap build,
jadi aplikasi lama harus di-uninstall dulu sebelum memasang versi baru.

## Menjalankan di komputer sendiri

```bash
pip install -r requirements.txt   # butuh juga ffmpeg
python scripts/gk2a_indonesia.py --site site --hours 3 --max-new 3
```

Opsi penting:
- `--hours`: panjang animasi.
- `--product`: pilih `true_color_night_clouds`, `true_color_raw_night_clouds`, `true_color`, atau `true_color_raw`.
- `--res`: resolusi pemrosesan dalam derajat.
- `--width`: lebar frame.
- `--end YYYYmmddHHMM`: membuat citra untuk waktu lampau.

Wilayah diatur lewat `INDONESIA_BBOX` di `scripts/gk2a_indonesia.py`.
Kalau wilayahnya diubah ke luar Indonesia, buat ulang `data/indonesia_lines.json` dengan `scripts/make_lines.py`.

## Catatan

- **Jeda data**: GK2A mengambil gambar tiap 10 menit, dan file muncul di AWS sekitar 20 menit kemudian.
  Jadwal GitHub Actions juga sering molor beberapa menit. Jadi citra terbaru biasanya tertinggal 25–40 menit dari waktu nyata.
- **Hemat unduhan**: data dibaca langsung dari S3 dengan HTTP range request. Hanya blok yang mencakup Indonesia
  yang diunduh, bukan file full disk utuh (sekitar 870 MB per waktu untuk 4 kanal visible).
- **Jadwal otomatis berhenti**: GitHub menonaktifkan jadwal di repo public yang tidak ada commit di branch utama selama 60 hari.
  Kalau berhenti, aktifkan lagi dari tab Actions.
- **Sumber data**: GK2A AMI L1B © KMA/NMSC, didistribusikan oleh NOAA Open Data Dissemination (NODD).
  Garis pantai: Natural Earth (public domain).
