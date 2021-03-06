package query

import ehr.clinical_documents.*
import ehr.clinical_documents.data.*

/**
 * Parametros n1 de la query:
 *  - ehrId    (== compoIndex.ehrId)
 *  - fromDate (<= compoIndex.startDate)
 *  - toDate   (>= compoIndex.startDate)
 *  - archetypeId (es parametro si no se especifica qarchetypeId en Query)
 * 
 * Parametros n2 de la query:
 *  - valores para cada DataCriteria (el tipo del valor depende del tipo del RM en DataCriteria.path)
 * 
 * @author pab
 * 
 * TODO: crear un servicio que devuelva la definicion de una consulta
 *       con nombres, tipos y obligatoriedad de parametros.
 *
 */
class Query {

   String uid = java.util.UUID.randomUUID() as String

   // Describe lo que hace la query
   String name
   
   // queryByData (composition) o queryData (datavalue)
   // lo que los diferencia es el resultado: composiciones o datos asociados a paths
   String type
   
   // Sino se especifica, por defecto es xml
   String format = 'xml'
   
   // Si es null, se puede especificar como parametro de la query
   // a modo de "tipo de documento", sino se especifica en ningun
   // caso, pide "cualquier tipo de documento". 
   String qarchetypeId
   
   // Si la consulta es de datos, se filtra por indices de nivel 1 y se usa DataGet para especificar que datos se quieren en el resultado.
   // Si la consulta es de compositions, se filtra por indices de nivel 1 y tambien por nivel 2 (para n2 se usa DataCriteria)
   // Los filtros/criterios de n1 y de n2 son parametros de la query.
   List select
   List where
   static hasMany = [select: DataGet, where: DataCriteria]
   
   // null, composition o path
   // Sirve para agrupar datos:
   //  composition: sirve para mostrar tablas, donde cada fila es una composition
   //  path: sirve para armar series de valores para graficar
   String group
   
   
   static constraints = {
      
      // para guardar la query debe tener nombre
      name(nullable:false, blank:false)
      
      // No creo que le guste null en inList, le pongo ''
      group(nullable:true, inList:['', 'composition', 'path'])
      qarchetypeId(nullable: true)
      format(inList:['xml','json'])
      type(inList:['composition','datavalue'])
   }
   
   static mapping = {
      group column: 'dg_group' // group es palabra reservada de algun dbms
   }
   
   
   def execute(String ehrId, Date from, Date to)
   {
      if (this.type == 'datavalue') return executeDatavalue(ehrId, from, to)
      return executeComposition(ehrId, from, to)
   }
   
   private executeDatavalue(String ehrId, Date from, Date to)
   {
      // Query data
      def res = DataValueIndex.withCriteria {
         
         // SELECT
         or { // matchea algun par archId+path
            this.select.each { dataGet ->
               
               and {
                  eq('archetypeId', dataGet.archetypeId)
                  eq('path', dataGet.path)
               }
            }
         }
         
         // WHERE level 1 filters
         owner { // CompositionIndex
            eq('ehrId', ehrId) // Ya se verifico que viene el param y que el ehr existe
            
            /*
            if (qarchetypeId)
            {
               eq('archetypeId', qarchetypeId) // Arquetipo de composition
            }
            */
            
            if (from) ge('startTime', from) // greater or equal
            if (to) le('startTime', to) // lower or equal
         }
      }
      
      //println res
      //println "group $group"
      
      // Group
      if (this.group == 'composition')
      {
         res = queryDataGroupComposition(res)
      }
      else if (this.group == 'path')
      {
         res = queryDataGroupPath(res)
      }
      
      return res
   }
   
     /**
    * Usada por queryData para agrupar por composition
    */
   private queryDataGroupComposition(res)
   {
      def resHeaders = [:]
      def dataidx
      
      // =========================================================================
      // TODO: obtener el nombre del arquetipo en cada path para usar de header
      // =========================================================================
      
      // Headers para la tabla: 1 col por path, y dentro de cada path 1 col por atributo del DataValue
      // h1: | path1 (DvQuantity) | path2 (DvCodedText) | ... |
      // h2: | magnitude | units  |   code   |  value   | ... |
      //
      // [
      //  path1: [ type:'DV_QUANTITY', attrs:['magnitude','units'] ],
      //  path2: [ type:'DV_CODED_TEXT', attrs:['code','value'],
      //  ...
      // ]
      
      this.select.each { dataGet ->
         
         // Lookup del tipo de objeto en la path para saber los nombres de los atributos
         // concretos por los cuales buscar (la path apunta a datavalue no a sus campos).
         dataidx = DataIndex.findByArchetypeIdAndPath(dataGet.archetypeId, dataGet.path)
         
         resHeaders[dataGet.path] = [:]
         resHeaders[dataGet.path]['type'] = dataidx.rmTypeName
         resHeaders[dataGet.path]['name'] = dataidx.name
         
         switch (dataidx.rmTypeName)
         {
            case ['DV_QUANTITY', 'DvQuantity']:
               resHeaders[dataGet.path]['attrs'] = ['magnitude', 'units']
            break
            case ['DV_CODED_TEXT', 'DvCodedText']:
               resHeaders[dataGet.path]['attrs'] = ['value']
            break
            case ['DV_DATE_TIME', 'DvDateTime']:
               resHeaders[dataGet.path]['attrs'] = ['code', 'value']
            break
            default:
               throw new Exception("type "+dataidx.rmTypeName+" not supported")
         }
      }
      
      
      // Filas de la tabla
      def resGrouped = [:]
      
      
      // DEBUG
      //println res as grails.converters.JSON
      

      // dvis por composition (Map[compo.id] = [dvi, dvi, ...])
      // http://groovy.codehaus.org/groovy-jdk/java/util/Collection.html#groupBy(groovy.lang.Closure)
      def rows = res.groupBy { it.owner.id } // as grails.converters.JSON
      
      //println rows
      
      def dvi
      def col // lista de valores de una columna
      rows.each { compoId, dvis ->
         
         //println compoId + ": " + dvis
         
         resGrouped[compoId] = [:]
         
         // Datos de la composition
         // FIXME: deberia haber por lo menos un dvi, sino esto da error
         resGrouped[compoId]['date'] = dvis[0].owner.startTime
         resGrouped[compoId]['uid']  = dvis[0].owner.uid
         resGrouped[compoId]['cols'] = []
         
         // Las columnas no incluyen la path porque se corresponden en el indice con la path en resHeaders
         // Cada columna de la fila
         resHeaders.each { path, colData -> // colData = [type:'XX', attrs:['cc','vv']]
            
            //println "header: " + path + " " + colData
            //resGrouped[compoId]['cols']['type'] = idxtype
            
            col = [type: colData['type'], path: path] // pongo la path para debug
            
            // dvi para la columna actual
            dvi = dvis.find{it.path == path && it.owner.id == compoId}
            
            if (dvi)
            {
               // Datos de cada path seleccionada dentro de la composition
               switch (colData['type'])
               {
                  case ['DV_QUANTITY', 'DvQuantity']:
                     col['magnitude'] = dvi.magnitude
                     col['units'] = dvi.units
                  break
                  case ['DV_CODED_TEXT', 'DvCodedText']:
                     col['value'] = dvi.value
                  break
                  case ['DV_DATE_TIME', 'DvDateTime']:
                     col['code'] = dvi.code
                     col['value'] = dvi.value
                  break
                  default:
                     throw new Exception("type "+colData['type']+" not supported")
               }
               
               resGrouped[compoId]['cols'] << col
            }
         }
      }
      
      return [resHeaders, resGrouped]
   } // queryDataGroupComposition
   
   /**
    * Usada por queryData para agrupar por path
    */
   private queryDataGroupPath(res)
   {
      // En este caso los headers son las filas
      //def resHeaders = [:]
      def dataidx
      
      // Columnas de la tabla (series)
      def resGrouped = [:]
      
      
      // TODO: necesito la fecha de la composition para cada item de la serie,
      //       el mismo indice en distintas series corresponde la misma fecha
      //       la fecha identifica la fila, y cada serie es una columna.
      
      // FIXME: deberia ser archId+path para que sea absoluta
      //        seria mas facil si archId y path fueran un solo campo
      def cols = res.groupBy { it.path }
      
      
      // TODO: cada serie debe tener el nombre de la path (lookup de DataIndex)
      
      this.select.each { dataGet ->
         
         // Lookup del tipo de objeto en la path para saber los nombres de los atributos
         // concretos por los cuales buscar (la path apunta a datavalue no a sus campos).
         dataidx = DataIndex.findByArchetypeIdAndPath(dataGet.archetypeId, dataGet.path)
         
         resGrouped[dataGet.path] = [:]
         resGrouped[dataGet.path]['type'] = dataidx.rmTypeName // type va en cada columna
         resGrouped[dataGet.path]['name'] = dataidx.name // name va en cada columna
         
         // FIXME: hay tipos de datos que no deben graficarse
         // TODO: entregar solo valores segun el tipo de dato, en lugar de devolver DataValueIndexes
         //resGrouped[paths[i]]['serie'] = cols[paths[i]]
         
         resGrouped[dataGet.path]['serie'] = []
         
         cols[dataGet.path].each { dvi ->
            
            // Datos de cada path seleccionada dentro de la composition
            switch (dataidx.rmTypeName)
            {
               case ['DV_QUANTITY', 'DvQuantity']: // FIXME: this is a bug on adl parser it uses Java types instead of RM ones
                  resGrouped[dataGet.path]['serie'] << [magnitude: dvi.magnitude,
                                                    units:     dvi.units,
                                                    date:      dvi.owner.startTime]
               break
               case ['DV_CODED_TEXT', 'DvCodedText']:
                  resGrouped[dataGet.path]['serie'] << [value:     dvi.value,
                                                    date:      dvi.owner.startTime]
               break
               case ['DV_DATE_TIME', 'DvDateTime']:
                  resGrouped[dataGet.path]['serie'] << [code:      dvi.code,
                                                    value:     dvi.value,
                                                    date:      dvi.owner.startTime]
               break
               default:
                  throw new Exception("type "+dataidx.rmTypeName+" not supported")
            }
            
            // para cada fila quiero fecha y uid de la composition
         }
      }
      
      return resGrouped
   }
   
   
   private executeComposition(String ehrId, Date from, Date to)
   {
       // Armado de la query
       String q = "FROM CompositionIndex ci WHERE "
       
       // ===============================================================
       // Criteria nivel 1 ehrId
       if (ehrId) q += "ci.ehrId = '" + ehrId + "' AND "
       
       // Criteria nivel 1 archetypeId (solo de composition)
       //if (qarchetypeId) q += "ci.archetypeId = '" + qarchetypeId +"' AND "
       
       // Criterio de rango de fechas para ci.startTime
       // Formatea las fechas al formato de la DB
       if (from) q += "ci.startTime >= '"+ formatterDateDB.format( from ) +"' AND " // higher or equal
       if (to) q += "ci.startTime <= '"+ formatterDateDB.format( to ) +"' AND " // lower or equal
       
       //
       // ===============================================================
       
       /**
        * FIXME: issue #6
        * si en el create se verifican las condiciones para que a aqui no
        * llegue una path a un tipo que no corresponde, el error de tipo
        * no sucederia nunca, asi no hay que tirar except aca.
        */
       def dataidx
       def idxtype
       this.where.eachWithIndex { dataCriteria, i ->
          
          // Lookup del tipo de objeto en la path para saber los nombres de los atributos
          // concretos por los cuales buscar (la path apunta a datavalue no a sus campos).
          dataidx = DataIndex.findByArchetypeIdAndPath(dataCriteria.archetypeId, dataCriteria.path)
          idxtype = dataidx?.rmTypeName
          
          
          // Subqueries sobre los DataValueIndex de los CompositionIndex
          q +=
          " EXISTS (" +
          "  SELECT dvi.id" +
          "  FROM DataValueIndex dvi" +
          "  WHERE dvi.owner.id = ci.id" + // Asegura de que todos los EXISTs se cumplen para el mismo CompositionIndex (los criterios se consideran AND, sin esta condicion es un OR y alcanza que se cumpla uno de los criterios que vienen en params)
          "        AND dvi.archetypeId = '"+ dataCriteria.archetypeId +"'" +
          "        AND dvi.path = '"+ dataCriteria.path +"'"
          
          // Consulta sobre atributos del DataIndex dependiendo de su tipo
          switch (idxtype)
          {
             case ['DV_DATE_TIME', 'DvDateTime']: // ADL Parser bug: uses Java class names instead of RM Type Names...
                q += "        AND dvi.value"+ dataCriteria..sqlOperand() + dataCriteria.value // TODO: verificar formato, transformar a SQL
             break
             case ['DV_QUANTITY', 'DvQuantity']: // ADL Parser bug: uses Java class names instead of RM Type Names...
                q += "        AND dvi.magnitude"+ dataCriteria.sqlOperand() + new Float(dataCriteria.value)
             break
             case ['DV_CODED_TEXT', 'DvCodedText']: // ADL Parser bug: uses Java class names instead of RM Type Names...
                q += "        AND dvi.code"+ dataCriteria..sqlOperand() +"'"+ dataCriteria.value+"'"
             break
             // TODO: are more types
             default:
               throw new Exception("type $idxtype not supported")
          }
          q += ")"
          
          
          // Agrega ANDs para los EXISTs, menos el ultimo
          if (i+1 < this.where.size()) q += " AND "
       }
       
       println q
       
       def cilist = CompositionIndex.findAll( q )
       
       return cilist
   }
}