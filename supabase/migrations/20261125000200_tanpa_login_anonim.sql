-- Tanpa layar login: aplikasi memakai Supabase Anonymous Sign-in (otomatis, tak terlihat pengguna).
-- Setiap instalasi aplikasi mendapat user anonim sendiri (peran `authenticated`, klaim is_anonymous).
-- Aturan:
--   * HP hanya bisa menambah, membaca, mengoreksi label, dan menghapus data MILIKNYA sendiri.
--   * Peneliti membaca SEMUA data via Dashboard / service_role (python -m fishfresh.primer pull).
--   * Geometri crop & identitas citra tetap tidak bisa diubah setelah unggah (trigger captures_guard).
-- Prasyarat: auth.enable_anonymous_sign_ins = true (supabase/config.jsonc).

-- created_by tidak lagi wajib terdaftar di tabel collectors
alter table public.captures    drop constraint if exists captures_created_by_fkey;
alter table public.predictions drop constraint if exists predictions_created_by_fkey;

-- Foto duplikat ditolak per pengguna (bukan global) agar tidak membocorkan keberadaan foto orang lain
alter table public.captures drop constraint if exists captures_raw_sha256_key;
create unique index if not exists captures_owner_sha on public.captures (created_by, raw_sha256);

drop policy if exists datasets_read on public.datasets;
create policy datasets_read on public.datasets for select to authenticated using (true);

drop policy if exists captures_read on public.captures;
create policy captures_read on public.captures for select to authenticated
  using (created_by = (select auth.uid()));

drop policy if exists captures_insert on public.captures;
create policy captures_insert on public.captures for insert to authenticated
  with check (
    created_by = (select auth.uid())
    and exists (select 1 from public.datasets d where d.id = dataset_id and d.is_open)
  );

drop policy if exists captures_update on public.captures;
create policy captures_update on public.captures for update to authenticated
  using (created_by = (select auth.uid()))
  with check (created_by = (select auth.uid()));

drop policy if exists captures_delete on public.captures;
create policy captures_delete on public.captures for delete to authenticated
  using (created_by = (select auth.uid()));

drop policy if exists predictions_read on public.predictions;
create policy predictions_read on public.predictions for select to authenticated
  using (created_by = (select auth.uid()));

drop policy if exists predictions_insert on public.predictions;
create policy predictions_insert on public.predictions for insert to authenticated
  with check (
    created_by = (select auth.uid())
    and exists (select 1 from public.captures c where c.id = capture_id and c.created_by = (select auth.uid()))
  );

drop policy if exists label_history_read on public.label_history;
create policy label_history_read on public.label_history for select to authenticated
  using (exists (select 1 from public.captures c
                 where c.id = capture_id and c.created_by = (select auth.uid())));

-- Storage: objek dimiliki pengunggahnya (kolom owner_id diisi otomatis oleh Supabase Storage)
drop policy if exists fish_eyes_insert on storage.objects;
create policy fish_eyes_insert on storage.objects for insert to authenticated
  with check (
    bucket_id = 'fish-eyes'
    and exists (select 1 from public.datasets d
                where d.id = (storage.foldername(storage.objects.name))[1] and d.is_open)
  );

drop policy if exists fish_eyes_read on storage.objects;
create policy fish_eyes_read on storage.objects for select to authenticated
  using (bucket_id = 'fish-eyes' and owner_id = (select auth.uid())::text);

drop policy if exists fish_eyes_delete on storage.objects;
create policy fish_eyes_delete on storage.objects for delete to authenticated
  using (bucket_id = 'fish-eyes' and owner_id = (select auth.uid())::text);
