from pydantic import BaseModel

from opspilot_agent.providers.mock import MockEmbeddingProvider, MockLLMProvider


class _DummySchema(BaseModel):
    value: str = "unset"


async def test_mock_llm_provider_returns_an_instance_of_the_requested_schema() -> None:
    provider = MockLLMProvider()

    result = await provider.structured_response(
        messages=[{"role": "user", "content": "hi"}],
        schema=_DummySchema,
        max_output_tokens=100,
        timeout_s=5,
    )

    assert isinstance(result, _DummySchema)


async def test_mock_embedding_provider_is_deterministic_and_the_right_shape() -> None:
    provider = MockEmbeddingProvider(dimensions=8)

    first = await provider.embed(["hello", "world"])
    second = await provider.embed(["hello", "world"])

    assert first == second
    assert len(first) == 2
    assert all(len(vector) == 8 for vector in first)


async def test_mock_embedding_provider_distinguishes_different_texts() -> None:
    provider = MockEmbeddingProvider(dimensions=8)

    vectors = await provider.embed(["alpha", "beta"])

    assert vectors[0] != vectors[1]
