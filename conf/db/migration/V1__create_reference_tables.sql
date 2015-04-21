-- CREATE OR REPLACE FUNCTION check_email(email text) RETURNS bool
-- LANGUAGE plperlu
-- AS $$
-- use Email::Address;
--
-- my @addresses = Email::Address->parse($_[0]);
-- return scalar(@addresses) > 0 ? 1 : 0;
-- $$;


