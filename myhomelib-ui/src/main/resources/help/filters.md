# Єдині фільтри

Фільтри мови, року, формату, local/read, rating range та unrated використовують один `BookFilterSpec` у navigation, SQL table queries і Lucene.

Quick filter колонки змінює той самий стан. AND вимагає виконання всіх умов; OR приймає книгу, що відповідає хоча б одній активній умові. Reset повертає профіль до стандартного стану.
