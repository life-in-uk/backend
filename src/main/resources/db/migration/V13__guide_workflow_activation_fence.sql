-- Strengthen the V12 public projection guard against transactions that started before activation.
-- Keep the existing V12 triggers; replace their function rather than adding duplicate triggers.
CREATE TABLE guide_workflow_fence (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    generation bigint NOT NULL DEFAULT 0
);

INSERT INTO guide_workflow_fence(singleton) VALUES (true);

CREATE FUNCTION advance_guide_workflow_fence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE guide_workflow_fence SET generation = generation + 1 WHERE singleton;
    IF NOT FOUND THEN RAISE EXCEPTION 'Guide workflow fence missing'; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER guide_workflow_activation_fence
    AFTER INSERT ON guide_workflow_activation
    FOR EACH ROW EXECUTE FUNCTION advance_guide_workflow_fence();

CREATE OR REPLACE FUNCTION protect_guide_projection_write() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE observed_generation bigint;
BEGIN
    -- FOR SHARE conflicts with activation's fence update. A stale REPEATABLE READ writer fails
    -- with serialization error rather than writing through an old pre-activation snapshot.
    SELECT generation INTO observed_generation
      FROM guide_workflow_fence WHERE singleton FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Guide workflow fence missing' USING ERRCODE = '23514'; END IF;

    IF EXISTS (SELECT 1 FROM guide_workflow_activation)
        AND current_setting('life_in_uk.guide_publication', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'Guide projection writes require GuidePublicationService' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
