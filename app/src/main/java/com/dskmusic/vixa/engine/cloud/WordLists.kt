package com.dskmusic.vixa.engine.cloud

import java.util.Locale

/** Listas de palabras prefijadas (es / en) y analisis de frecuencias de un texto. */
object WordLists {

    class Preset(val id: String, val nameEs: String, val nameEn: String, val es: String, val en: String) {
        fun name(es: Boolean) = if (es) nameEs else nameEn
        /** Las primeras palabras llevan mas peso para que la nube tenga jerarquia. */
        fun text(es: Boolean): String {
            val words = (if (es) this.es else en).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            return words.mapIndexed { i, w ->
                when {
                    i < 2 -> "$w:5"
                    i < 6 -> "$w:3"
                    i < 14 -> "$w:2"
                    else -> w
                }
            }.joinToString("\n")
        }
    }

    val presets = listOf(
        Preset("love", "Amor", "Love",
            "amor,te quiero,corazón,pasión,cariño,beso,abrazo,siempre,juntos,ternura,romance,ilusión,felicidad,eterno,dulce,mi vida,destino,alma,sonrisa,deseo,fidelidad,complicidad,magia,sueño,promesa,para siempre,querer,adorar,enamorado,mimos",
            "love,I love you,heart,passion,sweetheart,kiss,hug,always,together,tenderness,romance,joy,happiness,forever,sweet,my life,destiny,soul,smile,desire,loyalty,devotion,magic,dream,promise,adore,darling,cuddle,crush,affection"),
        Preset("music", "Música", "Music",
            "música,ritmo,melodía,canción,guitarra,piano,batería,bajo,voz,concierto,baile,armonía,nota,compás,acorde,festival,cantar,banda,solo,estribillo,letra,sonido,vinilo,micrófono,escenario,orquesta,violín,jazz,rock,pop,salsa,flamenco",
            "music,rhythm,melody,song,guitar,piano,drums,bass,voice,concert,dance,harmony,note,beat,chord,festival,sing,band,solo,chorus,lyrics,sound,vinyl,microphone,stage,orchestra,violin,jazz,rock,pop,soul,blues"),
        Preset("nature", "Naturaleza", "Nature",
            "naturaleza,árbol,bosque,río,montaña,flor,sol,luna,mar,lluvia,viento,hoja,cielo,tierra,fuego,agua,aire,selva,playa,nube,estrella,prado,semilla,raíz,primavera,verano,otoño,invierno,amanecer,atardecer,cascada,lago",
            "nature,tree,forest,river,mountain,flower,sun,moon,sea,rain,wind,leaf,sky,earth,fire,water,air,jungle,beach,cloud,star,meadow,seed,root,spring,summer,autumn,winter,sunrise,sunset,waterfall,lake"),
        Preset("motivation", "Motivación", "Motivation",
            "esfuerzo,constancia,valentía,sueños,actitud,éxito,disciplina,pasión,fuerza,coraje,perseverancia,confianza,superación,metas,positivo,ánimo,decisión,energía,inspiración,libertad,crecer,creer,lograr,avanzar,paciencia,humildad,determinación,foco,hazlo,tú puedes",
            "effort,consistency,courage,dreams,attitude,success,discipline,passion,strength,bravery,perseverance,confidence,growth,goals,positive,spirit,decision,energy,inspiration,freedom,believe,achieve,advance,patience,humility,determination,focus,just do it,you can,hustle,mindset"),
        Preset("birthday", "Cumpleaños", "Birthday",
            "cumpleaños,felicidades,fiesta,tarta,regalo,globos,velas,sorpresa,alegría,celebrar,brindis,confeti,música,baile,amigos,familia,deseos,sonrisas,abrazos,diversión,un año más,feliz día,pastel,invitados,canción,serpentinas,piñata,champán,ilusión,recuerdos",
            "birthday,congratulations,party,cake,gift,balloons,candles,surprise,joy,celebrate,toast,confetti,music,dance,friends,family,wishes,smiles,hugs,fun,another year,happy day,present,guests,song,streamers,pinata,champagne,excitement,memories"),
        Preset("family", "Familia", "Family",
            "familia,hogar,mamá,papá,hijos,abuelos,hermanos,unión,cariño,respeto,apoyo,tradición,casa,cena,juntos,infancia,raíces,herencia,amor,protección,risas,domingo,generaciones,tíos,primos,bebé,orgullo,confianza,lealtad,recuerdos",
            "family,home,mom,dad,children,grandparents,siblings,union,affection,respect,support,tradition,house,dinner,together,childhood,roots,heritage,love,protection,laughter,sunday,generations,aunts,cousins,baby,pride,trust,loyalty,memories"),
        Preset("friendship", "Amistad", "Friendship",
            "amistad,amigos,lealtad,confianza,risas,complicidad,apoyo,compañía,sinceridad,aventura,recuerdos,juntos,siempre,hermanos,respeto,cariño,escuchar,fiesta,viaje,secretos,generosidad,compartir,fidelidad,alegría,mejores,abrazo,unión,cafés,bromas,gracias",
            "friendship,friends,loyalty,trust,laughter,complicity,support,company,honesty,adventure,memories,together,always,brothers,respect,affection,listen,party,trip,secrets,generosity,share,faithful,joy,best,hug,bond,coffee,jokes,thanks"),
        Preset("travel", "Viajes", "Travel",
            "viaje,aventura,maleta,pasaporte,avión,mapa,playa,montaña,ciudad,hotel,ruta,destino,turismo,cultura,descubrir,explorar,vacaciones,brújula,tren,carretera,mochila,fotografía,recuerdos,sol,isla,cabaña,paisaje,gastronomía,mundo,libertad",
            "travel,adventure,suitcase,passport,plane,map,beach,mountain,city,hotel,route,destination,tourism,culture,discover,explore,holidays,compass,train,road,backpack,photography,memories,sun,island,cabin,landscape,gastronomy,world,freedom"),
        Preset("food", "Comida", "Food",
            "comida,delicioso,sabor,receta,cocina,chef,postre,pan,queso,fruta,verdura,pasta,pizza,ensalada,café,vino,dulce,picante,desayuno,almuerzo,cena,tapas,paella,tortilla,chocolate,helado,hamburguesa,sopa,especias,hambre",
            "food,delicious,flavor,recipe,kitchen,chef,dessert,bread,cheese,fruit,vegetables,pasta,pizza,salad,coffee,wine,sweet,spicy,breakfast,lunch,dinner,tapas,paella,omelette,chocolate,ice cream,burger,soup,spices,hungry"),
        Preset("sport", "Deporte", "Sports",
            "deporte,fútbol,baloncesto,tenis,natación,carrera,equipo,victoria,campeón,entrenar,gol,medalla,esfuerzo,juego,ciclismo,gimnasio,maratón,árbitro,afición,estadio,récord,velocidad,resistencia,salto,portero,ganar,pasión,liga,copa,fair play",
            "sport,football,basketball,tennis,swimming,race,team,victory,champion,train,goal,medal,effort,game,cycling,gym,marathon,referee,fans,stadium,record,speed,endurance,jump,goalkeeper,win,passion,league,cup,fair play"),
        Preset("christmas", "Navidad", "Christmas",
            "navidad,regalo,nieve,árbol,estrella,belén,turrón,villancico,paz,familia,Papá Noel,Reyes Magos,luces,cena,brindis,magia,ilusión,campana,adorno,invierno,trineo,renos,felicidad,mazapán,chimenea,calcetín,polvorón,amor,deseos,Nochebuena",
            "christmas,gift,snow,tree,star,nativity,carol,peace,family,Santa Claus,Three Kings,lights,dinner,toast,magic,excitement,bell,ornament,winter,sleigh,reindeer,happiness,fireplace,stocking,cookies,love,wishes,Christmas Eve,holly,mistletoe"),
        Preset("tech", "Tecnología", "Technology",
            "tecnología,internet,código,datos,robot,inteligencia artificial,nube,móvil,aplicación,software,red,algoritmo,pantalla,futuro,innovación,digital,seguridad,programa,servidor,chip,realidad virtual,drones,ciberseguridad,wifi,pixel,bit,sistema,automatización,startup,ciencia",
            "technology,internet,code,data,robot,artificial intelligence,cloud,mobile,app,software,network,algorithm,screen,future,innovation,digital,security,program,server,chip,virtual reality,drones,cybersecurity,wifi,pixel,bit,system,automation,startup,science"),
    )

}
