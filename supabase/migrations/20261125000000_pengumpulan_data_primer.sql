-- =====================================================================================
-- Backend Supabase: pengumpulan DATA PRIMER citra mata ikan dari aplikasi Android.
-- Jalankan sekali di Supabase Dashboard > SQL Editor (atau `supabase db push`).
--
-- Tidak ada server sendiri: aplikasi menulis langsung ke Postgres (REST) + Storage,
-- dibatasi Row Level Security. Kunci service_role HANYA di laptop peneliti (penarik data).
--
-- Prasyarat di Dashboard:
--   Authentication > Sign In / Providers > Email: aktif;  "Allow new users to sign up": MATI
--   (akun pengumpul dibuat admin, lalu didaftarkan ke tabel public.collectors)
-- =====================================================================================

-- ---------- Tim pengumpul (allowlist) -------------------------------------------------
create table if not exists public.collectors (
  user_id      uuid primary key references auth.users (id) on delete cascade,
  display_name text not null,
  role         text not null default 'collector' check (role in ('collector', 'admin')),
  active       boolean not null default true,
  created_at   timestamptz not null default now()
);

create or replace function public.is_collector() returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.collectors c where c.user_id = auth.uid() and c.active)
$$;

create or replace function public.is_admin() returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.collectors c
                 where c.user_id = auth.uid() and c.active and c.role = 'admin')
$$;

-- ---------- Dataset primer ------------------------------------------------------------
create table if not exists public.datasets (
  id          text primary key check (id ~ '^[a-z0-9_]+$'),
  name        text not null,
  description text,
  is_open     boolean not null default true,   -- false = pengumpulan ditutup (tidak bisa tambah foto)
  created_at  timestamptz not null default now()
);

insert into public.datasets (id, name, description) values
  ('primer_1', 'Data Primer 1', 'Pengambilan pasar - gelombang 1'),
  ('primer_2', 'Data Primer 2', 'Pengambilan pasar - gelombang 2')
on conflict (id) do nothing;

-- ---------- Foto (satu baris = satu jepretan) ----------------------------------------
create table if not exists public.captures (
  id            uuid primary key,                 -- dibuat di HP (idempoten saat unggah ulang)
  dataset_id    text not null references public.datasets (id),
  -- Identitas ikan FISIK. Beberapa foto dari ikan yang sama berbagi fish_code dan
  -- WAJIB berada di split yang sama (train/val/test) agar tidak bocor.
  fish_code     text not null check (length(fish_code) between 3 and 64),
  species       text not null check (length(species) between 2 and 80),
  label         text not null check (label in ('highly_fresh', 'fresh', 'not_fresh')),
  eye_side      text check (eye_side in ('left', 'right')),
  market        text,                             -- nama pasar / lokasi
  latitude      double precision check (latitude between -90 and 90),
  longitude     double precision check (longitude between -180 and 180),
  captured_at   timestamptz not null,
  -- Citra: foto asli (sudah tegak sesuai EXIF) + crop mata siap-model.
  raw_path      text not null,                    -- objek di bucket fish-eyes
  eye_path      text not null,
  raw_width     int not null check (raw_width > 0),
  raw_height    int not null check (raw_height > 0),
  raw_sha256    text not null check (raw_sha256 ~ '^[0-9a-f]{64}$'),
  -- Lingkaran crop dalam piksel foto asli (setelah EXIF transpose), sama dengan
  -- src/fishfresh/eye_roi.py::crop_eye: persegi berpusat (crop_cx, crop_cy) bersisi 2*crop_r
  -- (crop_r SUDAH termasuk margin 1,10 x ukuran mata), area luar foto dipad hitam,
  -- resize 224 LANCZOS, lalu piksel di luar lingkaran inscribed = RGB 0.
  crop_cx       real not null,
  crop_cy       real not null,
  crop_r        real not null check (crop_r > 0),
  crop_margin   real not null default 0.10,
  crop_adjusted boolean not null default false,   -- true = pengguna menggeser/memperbesar lingkaran
  sharpness_lapvar real,                          -- variance of Laplacian di area mata (kontrol kualitas)
  brightness_mean  real,
  device_model  text,
  app_version   text,
  notes         text check (length(notes) <= 500),
  created_by    uuid not null default auth.uid() references public.collectors (user_id),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  unique (raw_sha256)                             -- tolak foto identik terunggah dua kali
);

create index if not exists captures_dataset_label on public.captures (dataset_id, label);
create index if not exists captures_fish on public.captures (dataset_id, fish_code);

-- ---------- Skor prediksi model di HP (SEMUA kelas) ----------------------------------
create table if not exists public.predictions (
  id                 uuid primary key default gen_random_uuid(),
  capture_id         uuid not null references public.captures (id) on delete cascade,
  model_id           text not null,               -- vgg16 / resnet50 / inceptionv3
  model_version      text not null,               -- exported_at dari metadata model
  input_variant      text not null default 'eye_crop' check (input_variant in ('eye_crop', 'full')),
  score_highly_fresh real not null check (score_highly_fresh between 0 and 1),
  score_fresh        real not null check (score_fresh between 0 and 1),
  score_not_fresh    real not null check (score_not_fresh between 0 and 1),
  pred_label         text generated always as (
    case
      when score_highly_fresh >= score_fresh and score_highly_fresh >= score_not_fresh then 'highly_fresh'
      when score_fresh >= score_not_fresh then 'fresh'
      else 'not_fresh'
    end) stored,
  preprocess_ms      real,
  inference_ms       real,
  threads            int,
  quantization       text,
  -- Prediksi HARUS dihitung/ditampilkan SETELAH label dipilih (anti bias jangkar).
  shown_before_label boolean not null default false,
  created_by         uuid not null default auth.uid() references public.collectors (user_id),
  created_at         timestamptz not null default now(),
  unique (capture_id, model_id, model_version, input_variant)
);

-- ---------- Riwayat perubahan label (jejak audit) ------------------------------------
create table if not exists public.label_history (
  id          bigint generated always as identity primary key,
  capture_id  uuid not null references public.captures (id) on delete cascade,
  old_label   text,
  new_label   text not null,
  changed_by  uuid default auth.uid(),
  changed_at  timestamptz not null default now()
);

create or replace function public.captures_audit() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if tg_op = 'INSERT' then
    insert into public.label_history (capture_id, old_label, new_label) values (new.id, null, new.label);
  elsif new.label is distinct from old.label then
    insert into public.label_history (capture_id, old_label, new_label) values (new.id, old.label, new.label);
  end if;
  new.updated_at := now();
  return new;
end $$;

drop trigger if exists captures_audit_ins on public.captures;
create trigger captures_audit_ins after insert on public.captures
  for each row execute function public.captures_audit();
drop trigger if exists captures_audit_upd on public.captures;
create trigger captures_audit_upd before update on public.captures
  for each row execute function public.captures_audit();

-- Hanya kolom yang boleh dikoreksi pengumpul; identitas citra & geometri tidak bisa diubah.
create or replace function public.captures_guard() returns trigger
language plpgsql set search_path = '' as $$
begin
  if (new.id, new.dataset_id, new.raw_path, new.eye_path, new.raw_sha256, new.raw_width,
      new.raw_height, new.crop_cx, new.crop_cy, new.crop_r, new.crop_margin, new.created_by, new.captured_at)
     is distinct from
     (old.id, old.dataset_id, old.raw_path, old.eye_path, old.raw_sha256, old.raw_width,
      old.raw_height, old.crop_cx, old.crop_cy, old.crop_r, old.crop_margin, old.created_by, old.captured_at)
     and not public.is_admin() then
    raise exception 'Kolom citra/identitas tidak boleh diubah';
  end if;
  return new;
end $$;

drop trigger if exists captures_guard on public.captures;
create trigger captures_guard before update on public.captures
  for each row execute function public.captures_guard();

-- ---------- Row Level Security --------------------------------------------------------
alter table public.collectors    enable row level security;
alter table public.datasets      enable row level security;
alter table public.captures      enable row level security;
alter table public.predictions   enable row level security;
alter table public.label_history enable row level security;

drop policy if exists collectors_read on public.collectors;
create policy collectors_read on public.collectors for select to authenticated
  using (user_id = auth.uid() or public.is_admin());

drop policy if exists datasets_read on public.datasets;
create policy datasets_read on public.datasets for select to authenticated
  using (public.is_collector());

drop policy if exists captures_read on public.captures;
create policy captures_read on public.captures for select to authenticated
  using (public.is_collector());

drop policy if exists captures_insert on public.captures;
create policy captures_insert on public.captures for insert to authenticated
  with check (
    public.is_collector() and created_by = auth.uid()
    and exists (select 1 from public.datasets d where d.id = dataset_id and d.is_open)
  );

drop policy if exists captures_update on public.captures;
create policy captures_update on public.captures for update to authenticated
  using (created_by = auth.uid() or public.is_admin())
  with check (public.is_collector());

drop policy if exists captures_delete on public.captures;
create policy captures_delete on public.captures for delete to authenticated
  using (public.is_admin());

drop policy if exists predictions_read on public.predictions;
create policy predictions_read on public.predictions for select to authenticated
  using (public.is_collector());

drop policy if exists predictions_insert on public.predictions;
create policy predictions_insert on public.predictions for insert to authenticated
  with check (
    public.is_collector() and created_by = auth.uid()
    and exists (select 1 from public.captures c where c.id = capture_id and c.created_by = auth.uid())
  );

drop policy if exists label_history_read on public.label_history;
create policy label_history_read on public.label_history for select to authenticated
  using (public.is_collector());

-- ---------- Ringkasan untuk aplikasi & peneliti ---------------------------------------
-- Progres per dataset/spesies/kelas (ditampilkan di HP agar pengumpul tahu kelas yang kurang).
create or replace view public.v_progress with (security_invoker = true) as
select dataset_id, species, label,
       count(*)                  as n_photos,
       count(distinct fish_code) as n_fish
from public.captures
group by dataset_id, species, label;

-- Satu baris per foto + skor model terakhir; dipakai `python -m fishfresh.primer pull`.
create or replace view public.v_export with (security_invoker = true) as
select c.*,
       p.model_id, p.model_version, p.score_highly_fresh, p.score_fresh, p.score_not_fresh,
       p.pred_label, p.inference_ms, p.shown_before_label
from public.captures c
left join lateral (
  select * from public.predictions p
  where p.capture_id = c.id and p.input_variant = 'eye_crop'
  order by p.created_at desc limit 1
) p on true;

-- ---------- Storage: bucket privat --------------------------------------------------
-- Path objek: <dataset_id>/<fish_code>/<capture_id>_raw.jpg  dan  ..._eye.png
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('fish-eyes', 'fish-eyes', false, 10485760, array['image/jpeg', 'image/png'])
on conflict (id) do nothing;

drop policy if exists fish_eyes_insert on storage.objects;
create policy fish_eyes_insert on storage.objects for insert to authenticated
  with check (
    bucket_id = 'fish-eyes' and public.is_collector()
    and exists (select 1 from public.datasets d
                where d.id = (storage.foldername(storage.objects.name))[1] and d.is_open)
  );

drop policy if exists fish_eyes_read on storage.objects;
create policy fish_eyes_read on storage.objects for select to authenticated
  using (bucket_id = 'fish-eyes' and public.is_collector());

drop policy if exists fish_eyes_delete on storage.objects;
create policy fish_eyes_delete on storage.objects for delete to authenticated
  using (bucket_id = 'fish-eyes' and public.is_admin());
