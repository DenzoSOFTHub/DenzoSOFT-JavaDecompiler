/** Covariant return types and @Override. */
public class C_Covariant {

    public static class Animal {
        public Animal reproduce() {
            return new Animal();
        }

        public Object describe() {
            return "animal";
        }
    }

    public static class Cat extends Animal {
        // Covariant return: Cat is narrower than Animal.
        @Override
        public Cat reproduce() {
            return new Cat();
        }

        // Covariant return narrowing Object -> String.
        @Override
        public String describe() {
            return "cat";
        }
    }

    public static Cat breed() {
        Cat c = new Cat();
        return c.reproduce();
    }
}
