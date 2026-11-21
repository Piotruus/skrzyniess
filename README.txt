SKRZYNKI - plugin (Paper) + resource pack
=========================================
Wymagania: serwer Paper 1.21.4 lub nowszy, Java 21.

1) BUDOWANIE PLUGINU (jednorazowo)
   - zainstaluj JDK 21 i Apache Maven
   - w tym folderze:  mvn package
   - gotowy plik: target/Skrzynki.jar -> wrzuc do folderu plugins/ serwera
   - jesli masz nowsza wersje Paper, zmien <paper.version> w pom.xml

2) RESOURCE PACK
   - wgraj Skrzynki-ResourcePack.zip na hosting z bezposrednim linkiem (np. GitHub Release)
   - wpisz link w plugins/Skrzynki/config.yml -> resource-pack.url
   - sha1 w configu jest dla oryginalnego pliku zip (nie pakuj go ponownie)
   - jesli klient pisze, ze pack jest "niezgodny", zmien liczby w pack.mcmeta

3) KOMENDY (uprawnienie skrzynki.admin)
   /skrzynka <zielona|fioletowa|czerwona|zolta|rozowa|niebieska|lucky> [kat]
        stawia skrzynke tam, gdzie patrzysz; kat w stopniach, dowolny (np. 45 = po przekatnej)
        bez kata skrzynka patrzy na Ciebie
   /skrzynka obroc <kat>      - obraca najblizsza skrzynke
   /skrzynka usun             - usuwa najblizsza (6 blokow)
   /skrzynka lista | czysc | reload
   /skrzynka komenda dodaj <komenda>   - komenda odpalana z konsoli po kliknieciu skrzynki
        np. /skrzynka komenda dodaj give %player% diamond 1
   /skrzynka komenda lista | usun <nr>

4) ZACHOWANIE
   - skrzynki otwieraja sie i zamykaja co 15 s
   - Lucky Block skacze i kreci sie co 10-20 s, wokol niego caly czas krazy pierscien
   - gracz blizej niz 0.5 bloku od krawedzi skrzynki jest lekko odbijany
     (push.distance / push.power w config.yml, uprawnienie skrzynki.bypass wylacza odbijanie)
