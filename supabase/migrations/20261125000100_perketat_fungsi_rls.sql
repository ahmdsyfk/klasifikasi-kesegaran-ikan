-- Perbaikan dari `supabase db advisors`:
-- 1) Fungsi SECURITY DEFINER tidak boleh terekspos sebagai RPC publik -> pindah ke skema `private`
--    (tidak diekspos PostgREST); fungsi trigger dicabut hak EXECUTE-nya.
-- 2) auth.uid() di policy dibungkus (select ...) agar dievaluasi sekali per kueri, bukan per baris.

create schema if not exists private;
grant usage on schema private to authenticated;

create or replace function private.is_collector() returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.collectors c where c.user_id = (select auth.uid()) and c.active)
$$;

create or replace function private.is_admin() returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.collectors c
                 where c.user_id = (select auth.uid()) and c.active and c.role = 'admin')
$$;

revoke all on function private.is_collector(), private.is_admin() from public, anon;
grant execute on function private.is_collector(), private.is_admin() to authenticated;

-- captures_guard memakai is_admin -> arahkan ke private
create or replace function public.captures_guard() returns trigger
language plpgsql set search_path = '' as $$
begin
  if (new.id, new.dataset_id, new.raw_path, new.eye_path, new.raw_sha256, new.raw_width,
      new.raw_height, new.crop_cx, new.crop_cy, new.crop_r, new.crop_margin, new.created_by, new.captured_at)
     is distinct from
     (old.id, old.dataset_id, old.raw_path, old.eye_path, old.raw_sha256, old.raw_width,
      old.raw_height, old.crop_cx, old.crop_cy, old.crop_r, old.crop_margin, old.created_by, old.captured_at)
     and not private.is_admin() then
    raise exception 'Kolom citra/identitas tidak boleh diubah';
  end if;
  return new;
end $$;

revoke all on function public.captures_audit(), public.captures_guard() from public, anon, authenticated;

-- Policy ulang dengan fungsi private + (select auth.uid())
drop policy if exists collectors_read on public.collectors;
create policy collectors_read on public.collectors for select to authenticated
  using (user_id = (select auth.uid()) or (select private.is_admin()));

drop policy if exists datasets_read on public.datasets;
create policy datasets_read on public.datasets for select to authenticated
  using ((select private.is_collector()));

drop policy if exists captures_read on public.captures;
create policy captures_read on public.captures for select to authenticated
  using ((select private.is_collector()));

drop policy if exists captures_insert on public.captures;
create policy captures_insert on public.captures for insert to authenticated
  with check (
    (select private.is_collector()) and created_by = (select auth.uid())
    and exists (select 1 from public.datasets d where d.id = dataset_id and d.is_open)
  );

drop policy if exists captures_update on public.captures;
create policy captures_update on public.captures for update to authenticated
  using (created_by = (select auth.uid()) or (select private.is_admin()))
  with check ((select private.is_collector()));

drop policy if exists captures_delete on public.captures;
create policy captures_delete on public.captures for delete to authenticated
  using ((select private.is_admin()));

drop policy if exists predictions_read on public.predictions;
create policy predictions_read on public.predictions for select to authenticated
  using ((select private.is_collector()));

drop policy if exists predictions_insert on public.predictions;
create policy predictions_insert on public.predictions for insert to authenticated
  with check (
    (select private.is_collector()) and created_by = (select auth.uid())
    and exists (select 1 from public.captures c where c.id = capture_id and c.created_by = (select auth.uid()))
  );

drop policy if exists label_history_read on public.label_history;
create policy label_history_read on public.label_history for select to authenticated
  using ((select private.is_collector()));

drop policy if exists fish_eyes_insert on storage.objects;
create policy fish_eyes_insert on storage.objects for insert to authenticated
  with check (
    bucket_id = 'fish-eyes' and (select private.is_collector())
    and exists (select 1 from public.datasets d
                where d.id = (storage.foldername(storage.objects.name))[1] and d.is_open)
  );

drop policy if exists fish_eyes_read on storage.objects;
create policy fish_eyes_read on storage.objects for select to authenticated
  using (bucket_id = 'fish-eyes' and (select private.is_collector()));

drop policy if exists fish_eyes_delete on storage.objects;
create policy fish_eyes_delete on storage.objects for delete to authenticated
  using (bucket_id = 'fish-eyes' and (select private.is_admin()));

drop function if exists public.is_collector();
drop function if exists public.is_admin();
