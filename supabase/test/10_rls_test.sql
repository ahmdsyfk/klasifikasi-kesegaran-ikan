-- Uji perilaku RLS/trigger (mode tanpa login: user anonim per instalasi).
-- Gagal = ERROR (psql -v ON_ERROR_STOP=1).
\set ON_ERROR_STOP 1
insert into auth.users values ('00000000-0000-0000-0000-00000000000b'), ('00000000-0000-0000-0000-00000000000c');

create function pg_temp.as_user(uid text) returns void language plpgsql as $$
begin perform set_config('request.jwt.claim.sub', uid, false); execute 'set role authenticated'; end $$;
create function pg_temp.expect_fail(sql text, what text) returns void language plpgsql as $$
begin
  begin execute sql; exception when others then raise notice 'OK ditolak: % (%)', what, sqlerrm; return; end;
  raise exception 'SEHARUSNYA DITOLAK: %', what;
end $$;
create function pg_temp.ins(id text, sha text, ds text default 'primer_1') returns void language plpgsql as $$
begin
  insert into public.captures (id, dataset_id, fish_code, species, label, captured_at, raw_path, eye_path,
    raw_width, raw_height, raw_sha256, crop_cx, crop_cy, crop_r)
  values (id::uuid, ds, 'P1-001', 'Nila', 'fresh', now(), ds || '/P1-001/' || id || '_raw.jpg',
    ds || '/P1-001/' || id || '_eye.png', 3000, 4000, repeat(sha, 64), 1500, 1800, 300);
end $$;

-- 1) HP-B: tambah foto + prediksi + objek storage
select pg_temp.as_user('00000000-0000-0000-0000-00000000000b');
select pg_temp.ins('11111111-1111-1111-1111-111111111111', 'a');
insert into public.predictions (capture_id, model_id, model_version, score_highly_fresh, score_fresh, score_not_fresh)
values ('11111111-1111-1111-1111-111111111111', 'resnet50', '2026-11-25', 0.2, 0.7, 0.1);
insert into storage.objects (bucket_id, name) values ('fish-eyes', 'primer_1/P1-001/b_raw.jpg');
do $$ begin assert (select pred_label from public.predictions) = 'fresh', 'pred_label salah'; end $$;
-- 2) koreksi label -> jejak audit
update public.captures set label = 'highly_fresh';
do $$ begin assert (select count(*) from public.label_history) = 2, 'audit label tidak tercatat'; end $$;
-- 3) geometri/path tak bisa diubah
select pg_temp.expect_fail($q$ update public.captures set crop_r = 10 $q$, 'ubah geometri crop');
-- 4) foto duplikat oleh HP yang sama ditolak
select pg_temp.expect_fail($q$ select pg_temp.ins('22222222-2222-2222-2222-222222222222', 'a') $q$, 'foto duplikat');
-- 5) menulis atas nama HP lain ditolak
select pg_temp.expect_fail($q$ insert into public.captures (id, dataset_id, fish_code, species, label, captured_at,
  raw_path, eye_path, raw_width, raw_height, raw_sha256, crop_cx, crop_cy, crop_r, created_by) values
  (gen_random_uuid(), 'primer_1', 'P1-003', 'Nila', 'fresh', now(), 'x', 'y', 1, 1, repeat('b', 64), 1, 1, 1,
   '00000000-0000-0000-0000-00000000000c') $q$, 'insert atas nama HP lain');
reset role;

-- 6) HP-C: tidak melihat / mengubah / menghapus data HP-B
select pg_temp.as_user('00000000-0000-0000-0000-00000000000c');
do $$ begin
  assert (select count(*) from public.captures) = 0, 'HP-C bisa membaca data HP-B!';
  assert (select count(*) from public.predictions) = 0, 'HP-C bisa membaca prediksi HP-B!';
  assert (select count(*) from public.label_history) = 0, 'HP-C bisa membaca audit HP-B!';
  assert (select count(*) from storage.objects) = 0, 'HP-C bisa membaca foto HP-B!';
end $$;
update public.captures set label = 'not_fresh';
delete from public.captures;
delete from storage.objects;
select pg_temp.expect_fail($q$ insert into public.predictions (capture_id, model_id, model_version,
  score_highly_fresh, score_fresh, score_not_fresh) values ('11111111-1111-1111-1111-111111111111',
  'vgg16', 'x', 0.1, 0.1, 0.8) $q$, 'prediksi ke foto HP lain');
-- foto yang sama (sha sama) dari HP lain tetap boleh
select pg_temp.ins('33333333-3333-3333-3333-333333333333', 'a');
reset role;
do $$ begin
  assert (select label from public.captures where id = '11111111-1111-1111-1111-111111111111') = 'highly_fresh',
    'HP-C berhasil mengubah label HP-B!';
  assert (select count(*) from public.captures) = 2, 'jumlah captures salah';
  assert (select count(*) from storage.objects) = 1, 'HP-C berhasil menghapus foto HP-B!';
end $$;

-- 7) belum sign-in (peran anon) tidak bisa apa-apa
set role anon;
select pg_temp.expect_fail($q$ insert into storage.objects (bucket_id, name) values ('fish-eyes', 'primer_1/x/y.jpg') $q$,
  'unggah tanpa sesi');
select pg_temp.expect_fail($q$ select count(*) from public.captures $q$, 'baca tanpa sesi');
reset role;

-- 8) dataset ditutup -> unggahan baru ditolak
update public.datasets set is_open = false where id = 'primer_2';
select pg_temp.as_user('00000000-0000-0000-0000-00000000000b');
select pg_temp.expect_fail($q$ insert into storage.objects (bucket_id, name) values ('fish-eyes', 'primer_2/x/y.jpg') $q$,
  'unggah ke dataset tertutup');
select pg_temp.expect_fail($q$ select pg_temp.ins('44444444-4444-4444-4444-444444444444', 'd', 'primer_2') $q$,
  'foto ke dataset tertutup');
-- 9) HP-B boleh menghapus foto miliknya sendiri
delete from public.captures where id = '11111111-1111-1111-1111-111111111111';
delete from storage.objects;
reset role;
do $$ begin
  assert (select count(*) from public.captures) = 1, 'HP-B gagal menghapus miliknya';
  assert (select count(*) from storage.objects) = 0, 'HP-B gagal menghapus fotonya';
end $$;
\echo SEMUA UJI LULUS

-- 10) registry model: HP hanya membaca model aktif, tidak bisa menulis
reset role;
insert into public.models (id, scenario, scenario_name, arch, arch_name, input_variant, input_size, quantization,
  version, file_path, file_mb, file_sha256, is_default) values
  ('eye_mask-resnet50-s42', 'eye_mask', 'Crop mata', 'resnet50', 'ResNet50', 'eye_crop', 224, 'float16', 'v1',
   'eye_mask/resnet50-s42.tflite', 47.5, repeat('e', 64), true),
  ('baseline-vgg16-s42', 'baseline', 'Baseline', 'vgg16', 'VGG16', 'full', 224, 'float16', 'v1',
   'baseline/vgg16-s42.tflite', 29.4, repeat('f', 64), false);
update public.models set active = false where id = 'baseline-vgg16-s42';
select pg_temp.as_user('00000000-0000-0000-0000-00000000000c');
do $$ begin assert (select count(*) from public.models) = 1, 'model nonaktif terlihat'; end $$;
select pg_temp.expect_fail($q$ insert into public.models (id, scenario, scenario_name, arch, arch_name, input_variant,
  input_size, quantization, version, file_path, file_mb, file_sha256) values ('x', 'x', 'x', 'vgg16', 'x', 'full',
  224, 'x', 'x', 'x', 1, repeat('a', 64)) $q$, 'HP menulis registry model');
update public.models set active = true;
reset role;
do $$ begin assert (select count(*) from public.models where active) = 1, 'HP berhasil mengubah registry!'; end $$;
select pg_temp.expect_fail($q$ insert into public.models (id, scenario, scenario_name, arch, arch_name, input_variant,
  input_size, quantization, version, file_path, file_mb, file_sha256, is_default) values ('y', 'y', 'y', 'vgg16', 'y',
  'full', 224, 'y', 'y', 'y', 1, repeat('a', 64), true) $q$, 'dua model default');
\echo UJI REGISTRY LULUS
