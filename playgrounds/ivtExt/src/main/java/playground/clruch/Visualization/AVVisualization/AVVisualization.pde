/**
 * MATSim Network Display.
 * by Claudio Ruch.  
 * 
 * This file loads a MATSim network.xml file and displays
 * the road network.
 *
 **/
 
 
 void setup(){
   // ==============================================================
   // setup and variables
   // ==============================================================
   
   
   final int FieldSizeX = 3000;
   final int FieldSizeY = 2000;
   final int VertexColor = 255;
   final int EdgeColor  = 255;
   final float xOffs    = 50.0;
   final float yOffs    = 50.0;
   XML XMLRoadNewtork;
   
   // create a black background
   size(3000,2000);  //<>//
   background(0);
   

   
   // ==============================================================
   // extract vertices and edges from road network file
   // ==============================================================
   
   // read network XML file
   XMLRoadNewtork       =  loadXML("C:/Users/Claudio/Desktop/sioux-2016/sioux-2016/network.xml");
   XML[] XMLNodes       =  XMLRoadNewtork.getChildren("nodes");
   XML[] VertexChildren =  XMLNodes[0].getChildren("node");
   println(VertexChildren.length);
   println(VertexChildren[0].getFloat("x"));
   
   // store graph vertices
   int numVertices = VertexChildren.length;
   Vertex[] Vertices;
   Vertices = new Vertex[numVertices];
   
   for(int i = 0; i< VertexChildren.length;i++){
     Vertices[i] = new Vertex();
     Vertices[i].id   = VertexChildren[i].getString("id");
     Vertices[i].xpos = VertexChildren[i].getFloat("x");
     Vertices[i].ypos = VertexChildren[i].getFloat("y");
     Vertices[i].c    = VertexColor;
   }

   
   
   // find extreme vertices
   Float xMin =  3.40282347E+38; //<>//
   Float yMin =  3.40282347E+38;
   Float xMax =  -3.40282347E+38;
   Float yMax =  -3.40282347E+38;

   
   for(int i = 0; i<Vertices.length;i++){
     if(Vertices[i].xpos <  xMin) xMin = Vertices[i].xpos;
     if(Vertices[i].ypos <  yMin) yMin = Vertices[i].ypos;
     if(Vertices[i].xpos >  xMax) xMax = Vertices[i].xpos;
     if(Vertices[i].ypos >  yMax) yMax = Vertices[i].ypos;
   }
   
   
   // calculate pixel positions
   Float scl = min((FieldSizeX-2*xOffs)/(xMax-xMin),(FieldSizeY-2*yOffs)/(yMax-yMin));
   
   //xOffs+(xpos-xMin)*scl;
   for(int i = 0; i<Vertices.length; i++){
     Vertices[i].calcPixelPos(scl,xMin,yMin,xOffs,yOffs);
   }
   
   
   // store graph edges
   XML[] XMLLinks       =  XMLRoadNewtork.getChildren("links");
   XML[] EdgeChildren   =  XMLLinks[0].getChildren("link");
   println(EdgeChildren.length);
   println(EdgeChildren[0].getFloat("x"));
   
   
   int numEdges = EdgeChildren.length;
   Edge[] Edges;
   Edges = new Edge[numEdges];
   
   for(int i = 0; i< EdgeChildren.length;i++){
     Edges[i] = new Edge();
     Edges[i].id         = EdgeChildren[i].getString("id");
     Edges[i].from       = EdgeChildren[i].getString("from");
     Edges[i].to         = EdgeChildren[i].getString("to");
     Edges[i].numLength  = EdgeChildren[i].getFloat("length");
     Edges[i].modes      = EdgeChildren[i].getString("modes");
     Edges[i].capacity   = EdgeChildren[i].getFloat("capacity");
     Edges[i].permalanes = EdgeChildren[i].getFloat("permlanes");
     Edges[i].oneway     = boolean(EdgeChildren[i].getInt("oneway"));
     Edges[i].freespeed  = EdgeChildren[i].getFloat("freespeed");
     Edges[i].c          = EdgeColor;     
   }
   
 //<>//
   // ==============================================================
   // draw vertices and edges
   // ==============================================================
   //vertices
   for(int i = 0; i <Vertices.length; i++){
     //Vertices[i].display();
     Vertices[i].display();
   }
   
   // edges
   int VertID = -1;
   float[] LineCoord = {-1.0,-1.0,-1.0,-1.0};
   for(int i = 0; i < Edges.length; i++){
     // find start vertex and extract coordinates
     VertID = FindVertex(Vertices, Edges[i].from);
     LineCoord[0] = Vertices[VertID].xposPix;
     LineCoord[1] = Vertices[VertID].yposPix;
     
     // find end vertex
     VertID = FindVertex(Vertices, Edges[i].to);
     LineCoord[2] = Vertices[VertID].xposPix;
     LineCoord[3] = Vertices[VertID].yposPix;
     
     // print a line from start to end vertex
     line(LineCoord[0],LineCoord[1],LineCoord[2],LineCoord[3]);
     
   }
   

 }
 
  //<>//
 
// class to display vertices in the road network
class Vertex { 
  String id;
  color c;
  float xpos;
  float ypos;
  float xposPix;
  float yposPix;

  // Standard constructor
  Vertex(){
    id = "-1";
    c = 255;
    xpos = 0.0;
    ypos = 0.0;
    xposPix = 0.0;
    yposPix = 0.0;
  }

  // Constructor is defined with arguments.
  Vertex(String tempId, color tempC, float tempXpos, float tempYpos, float tempxposPix, float tempyposPix) { 
    id = tempId;
    c = tempC;
    xpos = tempXpos;
    ypos = tempYpos;
    xposPix = tempxposPix;
    yposPix = tempyposPix;
  }
  
  void calcPixelPos(float scl, float xMin, float yMin, float xOffs, float yOffs){
    xposPix = xOffs+(xpos-xMin)*scl;
    yposPix = yOffs+(ypos-yMin)*scl;
  }
  
  
  // Painting the vertex
  void display() {
    stroke(255);
    fill(c);
    ellipse(xposPix,yposPix,5,5);
  }
  
}

// class to display edges in the road network
class Edge { 
  String id;
  String from;
  String to;
  Float numLength;
  String modes;
  Float capacity;
  Float permalanes;
  boolean oneway;
  Float freespeed;
  color c;

  
  // Standard constructor
  Edge(){
    id    = "not initialized";
    from  = "not initialized";
    to    = "not initialized";
    numLength = -1.0;
    modes = "not initialized";
    capacity = -1.0;
    permalanes = -1.0;
    oneway = true;
    freespeed = -1.0;
    c = -1;
  }
  
  // Painting the edge
  void display() {
    println("edge here");
  }

}


int FindVertex(Vertex[] Vertices, String VertexID){
  int i = 0;
  while(i<Vertices.length){
    if(Vertices[i].id.equals(VertexID)){
      return i;
    }else
    i = i+1;
  }
  return -1;
}
  