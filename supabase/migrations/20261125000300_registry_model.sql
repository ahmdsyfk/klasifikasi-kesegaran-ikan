-- Registry model per SKENARIO penelitian (pola seperti Lensa Herbal: model dipilih di aplikasi).
-- Berkas .tflite disimpan di bucket `models` (maks 50 MB/berkas di paket Free: pakai float16) ;
-- HP mengunduh sekali lalu inferensi tetap di HP (offline).
-- Baris & berkas hanya ditulis peneliti lewat service_role:
--   uv run python -m fishfresh.export_mobile --run-name eye_mask --publish

create table if not exists public.models (
  id             text primary key,               -- <run_name>-<arch>-s<seed>, mis. eye_mask-resnet50-s42
  scenario       text not null,                  -- run_name: baseline, eye_mask, k2_feature_extraction, ...
  scenario_name  text not null,                  -- label singkat untuk UI
  scenario_desc  text,
  arch           text not null check (arch in ('vgg16', 'resnet50', 'inceptionv3', 'tiny-cnn')),
  arch_name      text not null,
  seed           int,
  input_variant  text not null check (input_variant in ('eye_crop', 'full')),
  input_size     int not null check (input_size > 0),
  classes        text[] not null default array['highly_fresh', 'fresh', 'not_fresh'],
  quantization   text not null,
  version        text not null,                  -- waktu ekspor ISO; berubah = HP mengunduh ulang
  file_path      text not null,                  -- objek di bucket `models`
  file_mb        real not null,
  file_sha256    text not null check (file_sha256 ~ '^[0-9a-f]{64}$'),
  -- metrik dari outputs/: f1_mfed, acc_mfed, f1_ffe, delta_f1, latency_ms (CPU 4 thread), params
  metrics        jsonb not null default '{}'::jsonb,
  sort_order     int not null default 100,
  is_default     boolean not null default false,
  active         boolean not null default true,
  created_at     timestamptz not null default now()
);

create unique index if not exists models_one_default on public.models (is_default) where is_default;

alter table public.models enable row level security;
drop policy if exists models_read on public.models;
create policy models_read on public.models for select to authenticated using (active);

-- Prediksi mencatat skenario & varian input agar analisis per skenario mudah
alter table public.predictions add column if not exists scenario text;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('models', 'models', false, 52428800, array['application/octet-stream'])
on conflict (id) do nothing;

drop policy if exists models_bucket_read on storage.objects;
create policy models_bucket_read on storage.objects for select to authenticated
  using (bucket_id = 'models');
