-- UUID only: suppress late worker events after erasure without retaining an account or itinerary.
-- Remove a marker only after all producer/stream/DLQ replays for that UUID are impossible.
CREATE TABLE user_service.recommend_cancellations (job_id UUID PRIMARY KEY);
