package io.modelbridge.core

import io.modelbridge.spi.ModelInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModelFilterTest {
    private val models = listOf(
        ModelInfo("a-1", "provider-a"),
        ModelInfo("b-1", "provider-b"),
        ModelInfo("a-2", "provider-a"),
    )

    @Test
    fun `all providers keeps insertion order`() {
        assertEquals(models, ModelFilter.byProvider(models, null, ModelInfo::providerId))
        assertEquals(models, ModelFilter.byProvider(models, ModelFilter.ALL_PROVIDERS, ModelInfo::providerId))
        assertEquals(models, ModelFilter.byProvider(models, "全部供应商", ModelInfo::providerId))
    }

    @Test
    fun `specific provider filters models and label stays readable`() {
        val filtered = ModelFilter.byProvider(models, "provider-a", ModelInfo::providerId)

        assertEquals(listOf("a-1", "a-2"), filtered.map { it.id })
        assertEquals(listOf("provider-a", "provider-b"), ModelFilter.providerIds(models, ModelInfo::providerId))
        assertEquals("全部供应商", ModelFilter.selectedLabel(null))
        assertEquals("provider-a", ModelFilter.selectedLabel("provider-a"))
    }
}
