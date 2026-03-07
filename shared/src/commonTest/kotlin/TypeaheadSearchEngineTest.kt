package io.github.karloti.typeahead

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TypeaheadSearchEngineTest {

    data class City(
        val id: String,
        val name: String,
        val population: Int
    )

    val cities = listOf(
        City("1", "Sofia", 1200000),
        City("2", "Plovdiv", 340000)
    )

    val countries = listOf(
        "Afghanistan", "Albania", "Algeria", "Andorra", "Angola", "Antigua and Barbuda",
        "Argentina", "Armenia", "Australia", "Austria", "Azerbaijan", "Bahamas",
        "Bahrain", "Bangladesh", "Barbados", "Belarus", "Belgium", "Belize",
        "Benin", "Bhutan", "Bolivia", "Bosnia and Herzegovina", "Botswana", "Brazil",
        "Brunei", "Bulgaria", "Burkina Faso", "Burundi", "Cabo Verde", "Cambodia",
        "Cameroon", "Canada", "Central African Republic", "Chad", "Chile", "China",
        "Colombia", "Comoros", "Congo (Congo-Brazzaville)", "Costa Rica", "Croatia",
        "Cuba", "Cyprus", "Czechia (Czech Republic)", "Denmark", "Djibouti", "Dominica",
        "Dominican Republic", "Ecuador", "Egypt", "El Salvador", "Equatorial Guinea",
        "Eritrea", "Estonia", "Eswatini", "Ethiopia", "Fiji", "Finland", "France",
        "Gabon", "Gambia", "Georgia", "Germany", "Ghana", "Greece", "Grenada",
        "Guatemala", "Guinea", "Guinea-Bissau", "Guyana", "Haiti", "Holy See",
        "Honduras", "Hungary", "Iceland", "India", "Indonesia", "Iran", "Iraq",
        "Ireland", "Israel", "Italy", "Ivory Coast", "Jamaica", "Japan", "Jordan",
        "Kazakhstan", "Kenya", "Kiribati", "Kuwait", "Kyrgyzstan", "Laos", "Latvia",
        "Lebanon", "Lesotho", "Liberia", "Libya", "Liechtenstein", "Lithuania",
        "Luxembourg", "Madagascar", "Malawi", "Malaysia", "Maldives", "Mali", "Malta",
        "Marshall Islands", "Mauritania", "Mauritius", "Mexico", "Micronesia",
        "Moldova", "Monaco", "Mongolia", "Montenegro", "Morocco", "Mozambique",
        "Myanmar (Burma)", "Namibia", "Nauru", "Nepal", "Netherlands", "New Zealand",
        "Nicaragua", "Niger", "Nigeria", "North Korea", "North Macedonia", "Norway",
        "Oman", "Pakistan", "Palau", "Palestine State", "Panama", "Papua New Guinea",
        "Paraguay", "Peru", "Philippines", "Poland", "Portugal", "Qatar", "Romania",
        "Russia", "Rwanda", "Saint Kitts and Nevis", "Saint Lucia",
        "Saint Vincent and the Grenadines", "Samoa", "San Marino", "Sao Tome and Principe",
        "Saudi Arabia", "Senegal", "Serbia", "Seychelles", "Sierra Leone", "Singapore",
        "Slovakia", "Slovenia", "Solomon Islands", "Somalia", "South Africa",
        "South Korea", "South Sudan", "Spain", "Sri Lanka", "Sudan", "Suriname",
        "Sweden", "Switzerland", "Syria", "Tajikistan", "Tanzania", "Thailand",
        "Timor-Leste", "Togo", "Tonga", "Trinidad and Tobago", "Tunisia", "Turkey",
        "Turkmenistan", "Tuvalu", "Uganda", "Ukraine", "United Arab Emirates",
        "United Kingdom", "United States of America", "Uruguay", "Uzbekistan",
        "Vanuatu", "Venezuela", "Vietnam", "Yemen", "Zambia", "Zimbabwe"
    )

    @Test
    fun testTypeaheadSearchFunctionality() = runTest {
        val searchEngine = TypeaheadSearchEngine<City>(textSelector = { it.name })

        launch {
            // 2. Добавяме елементите паралелно
            searchEngine.addAll(cities)

            // 3. Търсим. Връща Pair<City, Double>
            val results = searchEngine.find("sof", maxResults = 5)

            results.forEach { (city, score) ->
                println("Found city: ${city.name} with population ${city.population}. Score: $score")
            }

            // Можем да добавяме/махаме в реално време!
            searchEngine.add(City("3", "Varna", 330000))
            searchEngine.remove(cities[1])
        }
    }

    @Test
    fun testAddingCountriesAndSearching() = runTest {
        val searchEngine = TypeaheadSearchEngine<String>(textSelector = { it })

        launch {
            // Add country names to the search engine
            searchEngine.addAll(countries)

            // Perform a search query
            val results = searchEngine.find("bul", maxResults = 3)
            val scores = listOf(383.0, 197.0, 197.0)

            // Print results
            results.forEachIndexed { index, (country, score) ->
                println("Found country: $country with score: $score")
                assertEquals(score, scores[index], "Score mismatch for country $country")
            }

            // Verify search engine size
            println("Total indexed countries: ${searchEngine.size}")
        }
    }

    @Test
    fun testTypingSimulationWithScoreProgression() = runTest {
        val searchEngine = TypeaheadSearchEngine<String>(textSelector = { it })

        launch {
            // Add all countries to the search engine
            searchEngine.addAll(countries)

            // Simulate typing a country name character by character
            val queryToType = "Sweeden"

            for (i in 1..minOf(queryToType.length, 6)) {
                val partialQuery = queryToType.substring(0, i)
                println("\n=== Typing: '$partialQuery' with typing error of '$queryToType' ===")

                val results = searchEngine.find(partialQuery, maxResults = 5)

                if (results.isEmpty()) {
                    println("No results found")
                } else {
                    results.forEachIndexed { index, (country, score) ->
                        println("${index + 1}. $country - Score: $score")
                    }
                }
            }

            println("\nTyping simulation completed!")
        }
    }

}
