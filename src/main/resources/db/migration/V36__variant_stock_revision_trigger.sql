-- All stock writers, including historical native releases, share this rule.
CREATE FUNCTION advance_variant_stock_revision()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.variant_stock IS DISTINCT FROM OLD.variant_stock THEN
        NEW.stock_revision := OLD.stock_revision + 1;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER variants_stock_revision
BEFORE UPDATE OF variant_stock ON variants
FOR EACH ROW EXECUTE FUNCTION advance_variant_stock_revision();
