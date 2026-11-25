package dev.kartiks.animiru.miruroll

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object MiruRollFilters {

    open class SelectFilter(
        displayName: String,
        val options: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        options.map { it.first }.toTypedArray(),
    ) {
        val selectedValue get() = options.getOrElse(state) { options[0] }.second
    }

    class SortFilter : SelectFilter("Sort By", SORT_OPTIONS)
    class MediaFilter : SelectFilter("Media Type", MEDIA_OPTIONS)
    class CategoryFilter : SelectFilter("Category", CATEGORY_OPTIONS)

    val FILTER_LIST get() = AnimeFilterList(
        SortFilter(),
        MediaFilter(),
        CategoryFilter(),
    )

    val SORT_OPTIONS = arrayOf(
        Pair("Popularity", "popularity"),
        Pair("Newly Added", "newly_added"),
        Pair("Alphabetical", "alphabetical"),
    )

    val MEDIA_OPTIONS = arrayOf(
        Pair("All Media", ""),
        Pair("Series", "&type=series"),
        Pair("Movies", "&type=movie_listing"),
    )

    val CATEGORY_OPTIONS = arrayOf(
        Pair("All Categories", ""),
        Pair("Action", "&categories=action"),
        Pair("Adventure", "&categories=adventure"),
        Pair("Comedy", "&categories=comedy"),
        Pair("Drama", "&categories=drama"),
        Pair("Fantasy", "&categories=fantasy"),
        Pair("Isekai", "&categories=isekai"),
        Pair("Romance", "&categories=romance"),
        Pair("Sci-Fi", "&categories=sci-fi"),
        Pair("Slice of Life", "&categories=slice_of_life"),
        Pair("Supernatural", "&categories=supernatural"),
        Pair("Thriller", "&categories=thriller"),
    )

    data class FilterParams(
        val sort: String = "popularity",
        val media: String = "",
        val category: String = "",
    )

    fun getFilterParams(filters: AnimeFilterList): FilterParams {
        var sort = "popularity"
        var media = ""
        var category = ""

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sort = filter.selectedValue
                is MediaFilter -> media = filter.selectedValue
                is CategoryFilter -> category = filter.selectedValue
                else -> {}
            }
        }

        return FilterParams(sort, media, category)
    }
}
