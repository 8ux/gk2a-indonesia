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
