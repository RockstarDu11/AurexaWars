# AurexaWars - SkyWars pour Aurexalium (Spigot 1.8.8)

Chaque joueur apparait sur une ile. Les coffres sont remplis au debut (et de
nouveau apres 3 minutes). On pille, on construit des ponts, on elimine ses
adversaires. Le dernier en vie gagne. Tomber dans le vide = mort.

- Les eliminés deviennent spectateurs (ils volent et les joueurs en vie ne les voient pas)
- Le stuff d'un joueur mort tombe au sol
- La map est remise en etat a la fin (blocs poses / casses, objets au sol)
- Les seaux (eau / lave) sont desactives
- Points PlayerPoints a la fin : gagnant, dernier elimine, participation (config.yml)
- Panneaux pour rejoindre, lit "Quitter" en case 9, tableau lateral

## Compiler
Le fichier `.github/workflows/build.yml` compile le plugin a chaque commit sur
GitHub. Le jar est dans Actions > derniere execution > Artifacts.

## Creer une map
Construis ta map SkyWars avec des COFFRES (tous les coffres de la zone de jeu
seront remplis automatiquement), puis :

1. `/sw create map1`
2. `/sw setlobby map1`      (salle d'attente)
3. `/sw setspec map1`       (ou vont les spectateurs / elimines)
4. Sur chaque ile, au point d'apparition : `/sw addspawn map1`
   (1 ile = 1 joueur max, le nombre d'iles = le nombre de joueurs max)
5. Au milieu de la map : `/sw setcenter map1` (coffres "mid" avec le meilleur loot)
6. Zone de jeu : `/sw pos1` et `/sw pos2` (deux coins opposes, le BAS doit etre
   SOUS les iles : tomber en dessous = mort), puis `/sw setregion map1`
7. `/sw info map1` : tout doit etre vert
8. Panneau : pose un panneau vierge, `/sw sign map1`, clic droit dessus

## Jouer
`/sw join <map>`, `/sw leave`, `/sw list` (alias /aw, /wars) ou clic sur un panneau.

## Permission
`aurexawars.admin` : configurer les arenes (op par defaut).
