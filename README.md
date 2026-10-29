# RKM Server + RKM Chat — v1.4.0

Paket proyek Android untuk menjalankan server ringan RKM Chat pada tablet Android. Server menggunakan Java HTTP pada port **8080**, menyimpan akun/pesan/media pada penyimpanan privat aplikasi, dan menyajikan halaman web dari `app/src/main/assets/index.html`. Proyek minimum Android API 19; target build Android 35.

## Pembaruan v1.4.0

- UI RKM Chat diperbarui ke gaya bersih-terang dengan sudut membulat dan tab utama yang lebih jelas.
- Halaman Pengaturan mandiri mengelompokkan akun/koneksi, chat/penyimpanan, privasi, tema, dan notifikasi.
- Chat arsip kini menyediakan aksi Buka chat dan Keluarkan dari arsip.
- Polling pesan/panggilan/kontak/status dibuat bergiliran dengan pengunci agar permintaan tidak menumpuk pada tablet lambat.
- Tinggi area aplikasi mengikuti viewport visual pada browser HP agar composer lebih mudah tetap di atas keyboard.
- Pengaturan arsip/blokir, tema, dan beberapa preferensi masih khusus browser/perangkat; fitur itu belum otomatis tersinkron ke akun lain apabila API server belum mendukungnya.

## Perubahan v1.3.0

- **Kirim video di chat:** endpoint unggah media biner menulis file langsung ke penyimpanan, bukan menampung seluruh video sebagai Base64 di memori. Video ditampilkan dengan pemutar HTML5 dan dukungan HTTP byte ranges agar browser dapat melanjutkan/mencari posisi video.
- **Kirim dokumen/file:** lampiran dikirim ke server dan dapat diunduh oleh pihak dalam percakapan yang sama.
- **Video status:** status dapat berisi teks/foto (API lama) atau foto/video melalui endpoint media baru. Kedaluwarsa daftar status masih 24 jam.
- **Live dengan komentar:** endpoint sesi Live, join/leave, heartbeat, komentar, serta signaling offer/answer/ICE. UI web dapat memulai Live dan meminta penonton bergabung melalui WebRTC.
- **Penyimpanan terbatas:** ukuran maksimum per media 16 MB dan batas total folder media baru 512 MB. File lama tidak dihapus otomatis.
- **Versi aplikasi** naik ke 1.3.0 (versionCode 7). Kode akun, hash kata sandi, chat teks, kontak, foto, pesan suara, dan panggilan yang lama dipertahankan.

## Cara kerja media

Web mengunggah file mentah ke `POST /api/media/upload` dengan header `X-RKM-Token` dan `Content-Type` sesuai file. Parameter URL: `purpose=message` dengan `recipient=nomor-teman`, atau `purpose=status` dengan teks opsional. Server hanya menerima upload berukuran 1 byte sampai 16 MB. Media yang dikirim ke chat terhubung ke metadata pesan dan hanya bisa diunduh oleh pengirim/penerima yang terautentikasi.

Pemutar video membaca `GET /api/media?token=...&id=...`; server mendukung header `Range` dan menjawab `206 Partial Content` untuk permintaan byte-range. File disimpan sebagai `rk-media/<id>.bin` di direktori privat aplikasi, dengan MIME type dan nama file disimpan di metadata JSON.

## Cara kerja Live dan batasannya

- `GET /api/live?token=...` mendaftar siaran yang aktif.
- `POST /api/live/start` memulai sesi; `/api/live/stop` mengakhirinya.
- `/api/live/join`, `/api/live/leave`, dan `/api/live/heartbeat` mengelola keberadaan penonton.
- `/api/live/signal` serta `GET /api/live/signals` membawa pesan signaling WebRTC (offer, answer, ICE), **bukan video mentah**.
- `/api/live/comment` dan `GET /api/live/comments` menyimpan/mengambil komentar sesi.

Live ini adalah **WebRTC peer-to-peer**: video/audio mengalir langsung dari perangkat host ke masing-masing penonton, sementara tablet berfungsi sebagai signaling server. Implementasi UI membatasi sesi uji hingga 6 penonton aktif untuk menghindari klaim skala besar pada tablet. Kualitas bergantung pada HTTPS, izin kamera/mikrofon, STUN, firewall, jaringan, dan bandwidth upload host. Sebagian jaringan memerlukan TURN. Untuk ribuan penonton atau live yang stabil pada jaringan sulit, diperlukan media relay/SFU terpisah (mis. LiveKit/Janus/mediasoup) dan konfigurasi TURN; itu tidak disediakan oleh APK ini.

## Mengunggah untuk build GitHub Actions

Workflow `.github/workflows/build-apk.yml` menerima `RKM-Server-Source.zip` (atau `RK-Server-Source.zip`) di root repository. ZIP sumber yang diunduh dari paket ini menyimpan folder teratas `rk-server-android/`, sesuai dengan workflow.

1. Unduh `RKM-Server-Source.zip` dari hasil yang disediakan.
2. Di repositori GitHub server, ganti file sumber ZIP lama dengan ZIP baru dan beri nama persis `RKM-Server-Source.zip`.
3. Commit perubahan. Buka **Actions** dan jalankan **Build RKM Server APK** (atau tunggu workflow berjalan karena push).
4. Jika build berhasil, buka hasil run dan unduh artifact **RKM-Server-APK**. Instal `app-debug.apk` pada tablet server.
5. Pastikan aplikasi server berjalan di port 8080 dan tunnel lama tetap mengarah ke port tersebut. Tidak perlu mengubah alamat tunnel hanya karena API media baru ditambahkan.

Jika repositori menyimpan proyek Android langsung di root (bukan sumber ZIP), unggah isi folder `rk-server-android/` ke root repository, termasuk `.github/workflows/`.

## Batasan dan keamanan

- Server menyimpan data di penyimpanan privat aplikasi. Menghapus data aplikasi atau menghapus instalasi dapat menghilangkan data. Media yang tidak lagi dirujuk pesan tidak dibersihkan otomatis; folder media baru dibatasi 512 MB dan upload baru ditolak bila batas tercapai.
- Media upload dibatasi 16 MB per file; ukuran lebih besar perlu kompresi atau storage backend lain.
- Live bukan server SFU dan tidak merekam siaran. Jika host menutup tab, jaringan putus, atau proses server mati, siaran dapat berhenti. Komentar disimpan di tablet.
- Ini bukan aplikasi dengan enkripsi end-to-end, belum diaudit keamanan profesional, dan tidak boleh dipakai sebagai pengganti layanan komunikasi yang sudah teruji untuk data sensitif. Gunakan kata sandi unik dan jangan mempublikasikan kredensial.
- Build APK dan pengujian dua perangkat harus dilakukan di GitHub Actions/perangkat pengguna; lingkungan pembuatan ini tidak memiliki Android SDK penuh untuk menjalankan build final.


## RKM Chat Clean v11

Pembaruan UI memisahkan Pengaturan dari tab kontak, mengelompokkan profil/tampilan/privasi/arsip/ekspor/cadangan, dan merapikan tampilan chat untuk layar HP serta desktop. Penyesuaian viewport mengikuti keyboard pada browser yang mendukung Visual Viewport. Siklus sinkronisasi web dijalankan berurutan agar polling tidak menumpuk di tablet server yang lambat.

Catatan: beberapa preferensi (misalnya arsip, blokir, tema, dan pengecualian privasi status) tetap lokal di browser selama API server belum menyediakan penyimpanan lintas perangkat untuk fitur tersebut. Fitur yang membutuhkan server tambahan tidak ditandai seolah-olah sudah tersinkron.
