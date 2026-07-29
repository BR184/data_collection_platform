update sync_run_table_states
   set cursor_strategy = case
       when nullif(btrim(updated_at_column), '') is null then 'NONE'
       else 'PRIMARY_KEY_KEYSET'
   end;

update sync_run_table_states
   set last_cursor_pk = to_jsonb(string_to_array(last_cursor_pk, chr(31)))::text
 where nullif(btrim(last_cursor_pk), '') is not null
   and left(ltrim(last_cursor_pk), 1) <> '[';

update sync_run_table_tasks
   set cursor_pk = to_jsonb(string_to_array(cursor_pk, chr(31)))::text
 where nullif(btrim(cursor_pk), '') is not null
   and left(ltrim(cursor_pk), 1) <> '[';
